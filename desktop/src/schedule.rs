use crate::config::Config;
use crate::focus::{run_session, SessionConfig};
use crate::supabase::{Policy, RemoteSchedule};
use anyhow::Result;
use chrono::{DateTime, Datelike, Duration as ChronoDuration, Local, NaiveTime, TimeZone, Weekday};
use std::collections::HashSet;
use std::thread::sleep;
use std::time::{Duration, Instant};

const POLL_SECS: u64 = 20;

/// Poll DESKTOP schedules and auto-start sessions when due. Re-fetches (and
/// re-auths) in ≤5-min chunks so edits are picked up and the token stays fresh.
pub fn watch(cfg: &mut Config) -> Result<()> {
    let device_id = cfg.device_id_or_new()?;
    let name = gethostname::gethostname().to_string_lossy().to_string();
    println!("Watching schedules + live sessions as \"{name}\"… (Ctrl+C to stop)");

    let mut sb = crate::authed(cfg)?;
    let _ = sb.upsert_device(&device_id, &name);
    let mut last_auth = Instant::now();
    let mut handled_live: Option<String> = None;

    loop {
        // Access tokens expire (~1h); re-auth periodically.
        if last_auth.elapsed() >= Duration::from_secs(25 * 60) {
            match crate::authed(cfg) {
                Ok(c) => {
                    sb = c;
                    last_auth = Instant::now();
                    let _ = sb.upsert_device(&device_id, &name);
                }
                Err(e) => {
                    eprintln!("re-auth failed: {e}");
                    sleep(Duration::from_secs(60));
                    continue;
                }
            }
        }

        let policy = crate::resolve_policy(&sb, cfg);

        // 1) A live session started on another device (phone "sync to laptop").
        if let Ok(Some(a)) = sb.get_active_session() {
            if a.active
                && a.started_at.is_some()
                && a.started_at != handled_live
                && a.origin_device_id.as_deref() != Some(device_id.as_str())
            {
                handled_live = a.started_at.clone();
                println!("Joining a live focus session started on another device…");
                let scfg = session_from_config(&a.config, &policy);
                if let Err(e) = run_session(&sb, &device_id, &scfg) {
                    eprintln!("live session failed: {e}");
                }
                continue;
            }
        }

        // 2) Scheduled DESKTOP sessions.
        let now = Local::now();
        let schedules = sb.list_schedules().unwrap_or_default();
        let next = schedules
            .iter()
            .filter(|s| s.enabled && s.target_platforms.iter().any(|p| p.eq_ignore_ascii_case("DESKTOP")))
            .filter_map(|s| next_run(s, now).map(|t| (t, s)))
            .min_by_key(|(t, _)| *t);

        if let Some((at, sched)) = next {
            let wait = (at - now).num_seconds().max(0) as u64;
            if wait <= POLL_SECS {
                let title = if sched.title.is_empty() { "Focus".to_string() } else { sched.title.clone() };
                sleep(Duration::from_secs(wait));
                println!("Starting scheduled \"{title}\"…");
                let scfg = SessionConfig {
                    focus_min: sched.focus_block_min.max(1),
                    break_min: sched.break_min.max(0),
                    cycles: sched.cycles.max(1),
                    allowed_apps: policy.allowed_apps.clone(),
                    blocked_apps: policy.blocked_apps.clone(),
                    force_quit_apps: policy.force_quit_apps.clone(),
                    blocked_sites: policy.blocked_sites.clone(),
                    block_sites: true, // scheduled desktop sessions enforce the policy's site list
                    broadcast: false,
                };
                if let Err(e) = run_session(&sb, &device_id, &scfg) {
                    eprintln!("session failed: {e}");
                }
                continue;
            }
        }

        sleep(Duration::from_secs(POLL_SECS));
    }
}

/// Build a session from the phone's active_sessions.config + this laptop's policy.
fn session_from_config(config: &serde_json::Value, p: &Policy) -> SessionConfig {
    let get = |k: &str, d: i64| config.get(k).and_then(|v| v.as_i64()).unwrap_or(d);
    let block_sites = config.get("blockSites").and_then(|v| v.as_bool()).unwrap_or(false);
    SessionConfig {
        focus_min: get("focusBlockMin", 25).max(1),
        break_min: get("breakMin", 5).max(0),
        cycles: get("cycles", 1).max(1),
        allowed_apps: p.allowed_apps.clone(),
        blocked_apps: p.blocked_apps.clone(),
        force_quit_apps: p.force_quit_apps.clone(),
        blocked_sites: p.blocked_sites.clone(),
        block_sites,
        broadcast: false,
    }
}

fn next_run(sched: &RemoteSchedule, now: DateTime<Local>) -> Option<DateTime<Local>> {
    let (h, m) = parse_hhmm(&sched.start_local_time);
    let time = NaiveTime::from_hms_opt(h, m, 0)?;
    let days = parse_days(&sched.recurrence);
    for i in 0..8 {
        let date = (now + ChronoDuration::days(i)).date_naive();
        let runs = match &days {
            None => true,
            Some(set) => set.contains(&date.weekday()),
        };
        if !runs {
            continue;
        }
        let dt = Local.from_local_datetime(&date.and_time(time)).single()?;
        if dt > now {
            return Some(dt);
        }
    }
    None
}

fn parse_hhmm(s: &str) -> (u32, u32) {
    let mut parts = s.split(':');
    let h = parts.next().and_then(|x| x.parse().ok()).unwrap_or(9);
    let m = parts.next().and_then(|x| x.parse().ok()).unwrap_or(0);
    (h, m)
}

/// None = every day; Some(set) = only those weekdays.
fn parse_days(recurrence: &serde_json::Value) -> Option<HashSet<Weekday>> {
    let t = recurrence.get("type").and_then(|v| v.as_str()).unwrap_or("DAILY");
    if t.eq_ignore_ascii_case("DAILY") {
        return None;
    }
    let mut set = HashSet::new();
    if let Some(arr) = recurrence.get("days").and_then(|v| v.as_array()) {
        for d in arr {
            if let Some(w) = d.as_str().and_then(weekday_from) {
                set.insert(w);
            }
        }
    }
    Some(set)
}

fn weekday_from(name: &str) -> Option<Weekday> {
    match name.to_uppercase().as_str() {
        "MONDAY" => Some(Weekday::Mon),
        "TUESDAY" => Some(Weekday::Tue),
        "WEDNESDAY" => Some(Weekday::Wed),
        "THURSDAY" => Some(Weekday::Thu),
        "FRIDAY" => Some(Weekday::Fri),
        "SATURDAY" => Some(Weekday::Sat),
        "SUNDAY" => Some(Weekday::Sun),
        _ => None,
    }
}
