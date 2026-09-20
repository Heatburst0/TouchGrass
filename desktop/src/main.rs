mod config;
mod focus;
mod schedule;
mod supabase;
mod tracker;
mod enforce;
mod hosts;

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
    // Safety net: if the agent is Ctrl-C'd mid-session, undo any hosts-file block.
    let _ = ctrlc::set_handler(|| {
        hosts::clear();
        std::process::exit(130);
    });
    match Cli::parse().command {
        Command::Login => login(),
        Command::Focus { minutes, cycles, break_min } => focus_cmd(minutes, cycles, break_min),
        Command::Run => run_cmd(),
        Command::Status => status_cmd(),
    }
}

/// Refresh the access token from the stored refresh token, persisting the rotated one.
pub(crate) fn authed(cfg: &mut Config) -> Result<Supabase> {
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
    println!("Sent a sign-in email to {email}.");
    println!("Open it, copy the \"Sign in\" link, and paste it here.");
    println!("(Or, if you set up SMTP and added a code to the template, paste the 6-digit code.)");
    let input = prompt("> ")?;
    let session = if input.contains("token") || input.contains("://") {
        let token_hash =
            extract_token(&input).context("couldn't find a token in that link — paste the full URL")?;
        sb.verify_token_hash(&token_hash)?
    } else {
        sb.verify_otp(&email, input.trim())?
    };

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
    let p = resolve_policy(&sb, &cfg);
    let scfg = SessionConfig {
        focus_min: minutes.max(1),
        break_min: break_min.max(0),
        cycles: cycles.max(1),
        allowed_apps: p.allowed_apps,
        blocked_apps: p.blocked_apps,
        force_quit_apps: p.force_quit_apps,
        blocked_sites: p.blocked_sites,
        broadcast: false,
    };
    run_session(&sb, &device_id, &scfg)
}

/// The effective desktop rules: the server focus_policy if it has anything, else
/// seed it from agent.toml (so the phone gets a starting point) and use that.
pub(crate) fn resolve_policy(sb: &Supabase, cfg: &Config) -> supabase::Policy {
    if let Ok(Some(p)) = sb.get_policy() {
        if !p.allowed_apps.is_empty()
            || !p.blocked_apps.is_empty()
            || !p.force_quit_apps.is_empty()
            || !p.blocked_sites.is_empty()
        {
            return p;
        }
    }
    let seed = supabase::Policy {
        allowed_apps: cfg.allowed_apps.clone(),
        blocked_apps: cfg.blocked_apps.clone(),
        force_quit_apps: cfg.force_quit_apps.clone(),
        blocked_sites: cfg.blocked_sites.clone(),
    };
    let _ = sb.upsert_policy(&seed);
    seed
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

/// Pull the verification token out of a pasted Supabase sign-in link.
fn extract_token(link: &str) -> Option<String> {
    for key in ["token_hash=", "token="] {
        if let Some(i) = link.find(key) {
            let rest = &link[i + key.len()..];
            let end = rest.find('&').unwrap_or(rest.len());
            let val = rest[..end].trim();
            if !val.is_empty() {
                return Some(val.to_string());
            }
        }
    }
    None
}

fn prompt(label: &str) -> Result<String> {
    print!("{label}");
    io::stdout().flush()?;
    let mut s = String::new();
    io::stdin().read_line(&mut s)?;
    Ok(s.trim().to_string())
}
