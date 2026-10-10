mod config;
mod enforce;
mod focus;
mod hosts;
mod notify;
mod overlay;
mod schedule;
mod supabase;
mod tracker;
#[cfg(windows)]
mod tray;

use anyhow::{anyhow, bail, Context, Result};
use clap::{Parser, Subcommand};
use config::Config;
use focus::{run_session, SessionConfig};
use std::io::{self, Write};
use supabase::Supabase;

#[derive(Parser)]
#[command(
    name = "touchgrass-agent",
    about = "TouchGrass laptop focus agent — tracks productive time, synced to your phone."
)]
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
        /// Null-route your policy's blocked sites for the session (needs admin).
        #[arg(long = "block-sites", default_value_t = false)]
        block_sites: bool,
    },
    /// Watch DESKTOP schedules and auto-start sessions.
    Run,
    /// Show sign-in / config status.
    Status,
}

fn main() -> Result<()> {
    // Must precede any window creation (tray icon, overlays) or they render blurry.
    overlay::set_dpi_aware();
    // Safety net: if the agent is Ctrl-C'd mid-session, undo any hosts-file block.
    let _ = ctrlc::set_handler(|| {
        hosts::clear();
        std::process::exit(130);
    });
    let result = match Cli::parse().command {
        Command::Login => login(),
        Command::Focus {
            minutes,
            cycles,
            break_min,
            block_sites,
        } => focus_cmd(minutes, cycles, break_min, block_sites),
        Command::Run => run_cmd(),
        Command::Status => status_cmd(),
    };
    if let Err(e) = &result {
        log_error(e);
    }
    result
}

/// Append an error to agent.log — the `run` agent is hidden, so stderr goes nowhere.
fn log_error(e: &anyhow::Error) {
    let Ok(path) = Config::log_path() else { return };
    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        let _ = writeln!(
            file,
            "{} {e:#}",
            chrono::Local::now().format("%Y-%m-%d %H:%M:%S")
        );
    }
}

/// Refresh the access token from the stored refresh token, persisting the rotated one.
///
/// Supabase rotates the refresh token on every use, so the refresh + save runs
/// under a cross-process lock, starting from the token currently on disk (another
/// agent process may have rotated it since this one loaded its config).
pub(crate) fn authed(cfg: &mut Config) -> Result<Supabase> {
    let _lock = Config::auth_lock()?;
    if let Ok(disk) = Config::load() {
        if disk.refresh_token.is_some() {
            cfg.refresh_token = disk.refresh_token;
        }
    }
    let mut sb = Supabase::new(&cfg.supabase_url, &cfg.anon_key);
    let rt = cfg
        .refresh_token
        .clone()
        .context("not signed in — run `touchgrass-agent login`")?;
    let session = sb.refresh(&rt).map_err(|e| {
        let text = e.to_string();
        if text.contains("refresh_token_already_used") || text.contains("refresh_token_not_found") {
            anyhow!("sign-in expired — run `touchgrass-agent login` again")
        } else {
            e
        }
    })?;
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
    println!("In the email, right-click \"Sign in\" -> Copy link address, and paste it here.");
    println!("(Already clicked it? Paste the URL from the browser's address bar instead.)");
    let input = prompt("> ")?;
    let session = if let Some(refresh_token) = url_param(&input, "refresh_token") {
        // The link was opened: the browser landed on the redirect URL, whose
        // fragment already carries a session — exchange its refresh token.
        sb.refresh(&refresh_token)?
    } else if input.contains("://") || input.contains("token") {
        let token_hash = url_param(&input, "token_hash")
            .or_else(|| url_param(&input, "token"))
            .context("couldn't find a token in that link — paste the full URL")?;
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

fn focus_cmd(minutes: i64, cycles: i64, break_min: i64, block_sites: bool) -> Result<()> {
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
        block_sites,
        watch_remote_stop: false,
        broadcast: false,
    };
    // Pump Win32 messages on this thread so the on-screen timer can draw.
    overlay::run_blocking(move || run_session(&sb, &device_id, &scfg))
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
    // One watcher at a time — a second would race the first for the session token.
    let Some(_run_lock) = Config::try_run_lock()? else {
        bail!("another TouchGrass agent is already running");
    };
    let mut cfg = Config::load()?;
    // Validate we can auth before entering the watch loop.
    if let Err(e) = authed(&mut cfg) {
        alert_startup_failure(&e);
        return Err(e);
    }
    hosts::clear(); // drop any stale block left by a previous unclean exit
    run_watch(cfg)
}

/// The `run` agent has no console, so show a startup failure on screen (and in
/// the Action Center) instead of exiting silently.
fn alert_startup_failure(e: &anyhow::Error) {
    let msg = format!("{e:#}");
    notify::toast("TouchGrass agent couldn't start", &msg);
    let _ = overlay::run_blocking(move || {
        overlay::banner("TouchGrass agent couldn't start", &msg, overlay::Tone::Bad);
        Ok(())
    });
}

/// On Windows, run under a tray icon (Status / Stop); elsewhere just watch.
#[cfg(windows)]
fn run_watch(cfg: Config) -> Result<()> {
    tray::run(cfg)
}

#[cfg(not(windows))]
fn run_watch(mut cfg: Config) -> Result<()> {
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
    println!(
        "Device id: {}",
        cfg.device_id.clone().unwrap_or_else(|| "(none yet)".into())
    );
    println!("Allowed apps: {}", cfg.allowed_apps.join(", "));
    Ok(())
}

/// Pull the verification token out of a pasted Supabase sign-in link.
/// Value of query/fragment parameter `name` in a pasted URL. Matches whole
/// parameter names only, so `token` never matches inside `access_token`.
fn url_param(link: &str, name: &str) -> Option<String> {
    let key = format!("{name}=");
    let mut from = 0;
    while let Some(offset) = link[from..].find(&key) {
        let i = from + offset;
        let at_boundary = i == 0 || matches!(link.as_bytes()[i - 1], b'?' | b'&' | b'#');
        if at_boundary {
            let rest = &link[i + key.len()..];
            let end = rest.find(['&', '#']).unwrap_or(rest.len());
            let value = rest[..end].trim();
            if !value.is_empty() {
                return Some(value.to_string());
            }
        }
        from = i + key.len();
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

#[cfg(test)]
mod tests {
    use super::url_param;

    #[test]
    fn reads_token_from_the_emailed_verify_link() {
        let link = "https://x.supabase.co/auth/v1/verify?token=abc123&type=magiclink&redirect_to=http://localhost:3000";
        assert_eq!(url_param(link, "token").as_deref(), Some("abc123"));
        assert_eq!(url_param(link, "token_hash"), None);
    }

    #[test]
    fn reads_refresh_token_from_the_redirect_fragment() {
        let link = "http://localhost:3000/#access_token=eyJhbGc&expires_in=3600&refresh_token=rt123&token_type=bearer";
        assert_eq!(url_param(link, "refresh_token").as_deref(), Some("rt123"));
    }

    #[test]
    fn token_does_not_match_inside_access_token_or_token_type() {
        let link = "http://localhost:3000/#access_token=eyJhbGc&token_type=bearer";
        assert_eq!(url_param(link, "token"), None);
    }

    #[test]
    fn reads_token_hash_param() {
        let link = "https://x.supabase.co/auth/v1/verify?token_hash=h4sh&type=magiclink";
        assert_eq!(url_param(link, "token_hash").as_deref(), Some("h4sh"));
    }

    #[test]
    fn missing_or_empty_param_is_none() {
        assert_eq!(url_param("https://example.com/?token=", "token"), None);
        assert_eq!(url_param("123456", "token"), None);
    }
}
