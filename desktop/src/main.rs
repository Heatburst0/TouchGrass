mod config;
mod focus;
mod schedule;
mod supabase;
mod tracker;

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use config::Config;
use focus::{run_session, SessionConfig};
use std::io::{self, Write};
use supabase::Supabase;

#[derive(Parser)]
#[command(name = "touchgrass-agent", about = "TouchGrass laptop focus agent — tracks productive time, synced to your phone.")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Sign in with an email one-time code, and register this device.
    Login,
    /// Run a focus session now.
    Focus {
        #[arg(long, default_value_t = 25)]
        minutes: i64,
        #[arg(long, default_value_t = 1)]
        cycles: i64,
        #[arg(long = "break", default_value_t = 5)]
        break_min: i64,
    },
    /// Watch DESKTOP schedules and auto-start sessions.
    Run,
    /// Show sign-in / config status.
    Status,
}

fn main() -> Result<()> {
    match Cli::parse().command {
        Command::Login => login(),
        Command::Focus { minutes, cycles, break_min } => focus_cmd(minutes, cycles, break_min),
        Command::Run => run_cmd(),
        Command::Status => status_cmd(),
    }
}

/// Refresh the access token from the stored refresh token, persisting the rotated one.
fn authed(cfg: &mut Config) -> Result<Supabase> {
    let mut sb = Supabase::new(&cfg.supabase_url, &cfg.anon_key);
    let rt = cfg
        .refresh_token
        .clone()
        .context("not signed in — run `touchgrass-agent login`")?;
    let session = sb.refresh(&rt)?;
    cfg.refresh_token = Some(session.refresh_token);
    cfg.save()?;
    sb.set_access(session.access_token);
    Ok(sb)
}

fn hostname() -> String {
    gethostname::gethostname().to_string_lossy().to_string()
}

fn login() -> Result<()> {
    let mut cfg = Config::load()?;
    let sb = Supabase::new(&cfg.supabase_url, &cfg.anon_key);
    let email = prompt("Email: ")?;
    sb.send_otp(&email)?;
    println!("Sent a 6-digit code to {email}. Check your email.");
    let code = prompt("Code: ")?;
    let session = sb.verify_otp(&email, code.trim())?;

    cfg.email = Some(email);
    cfg.refresh_token = Some(session.refresh_token);
    let device_id = cfg.device_id_or_new()?;
    cfg.save()?;

    let mut sb2 = Supabase::new(&cfg.supabase_url, &cfg.anon_key);
    sb2.set_access(session.access_token);
    let name = hostname();
    sb2.upsert_device(&device_id, &name)?;
    println!("Signed in. Registered this device as \"{name}\".");
    Ok(())
}

fn focus_cmd(minutes: i64, cycles: i64, break_min: i64) -> Result<()> {
    let mut cfg = Config::load()?;
    let sb = authed(&mut cfg)?;
    let device_id = cfg.device_id_or_new()?;
    sb.upsert_device(&device_id, &hostname())?;
    let scfg = SessionConfig {
        focus_min: minutes.max(1),
        break_min: break_min.max(0),
        cycles: cycles.max(1),
        allowed_apps: cfg.allowed_apps.clone(),
    };
    run_session(&sb, &device_id, &scfg)
}

fn run_cmd() -> Result<()> {
    let mut cfg = Config::load()?;
    // Validate we can auth before entering the watch loop.
    authed(&mut cfg)?;
    schedule::watch(&mut cfg)
}

fn status_cmd() -> Result<()> {
    let cfg = Config::load()?;
    println!("Config: {}", Config::path()?.display());
    println!("Supabase: {}", cfg.supabase_url);
    println!("Signed in: {}", cfg.refresh_token.is_some());
    if let Some(email) = &cfg.email {
        println!("Email: {email}");
    }
    println!("Device id: {}", cfg.device_id.clone().unwrap_or_else(|| "(none yet)".into()));
    println!("Allowed apps: {}", cfg.allowed_apps.join(", "));
    Ok(())
}

fn prompt(label: &str) -> Result<String> {
    print!("{label}");
    io::stdout().flush()?;
    let mut s = String::new();
    io::stdin().read_line(&mut s)?;
    Ok(s.trim().to_string())
}
