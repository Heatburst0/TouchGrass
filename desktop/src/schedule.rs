use crate::config::Config;
use crate::focus::{run_session, SessionConfig};
use crate::supabase::{RemoteSchedule, Supabase};
use anyhow::Result;
use chrono::{DateTime, Datelike, Duration as ChronoDuration, Local, NaiveTime, TimeZone, Weekday};
use std::collections::HashSet;
use std::thread::sleep;
use std::time::Duration;

/// Poll DESKTOP schedules and auto-start sessions when due. Re-fetches (and
/// re-auths) in ≤5-min chunks so edits are picked up and the token stays fresh.
pub fn watch(cfg: &mut Config) -> Result<()> {
    let device_id = cfg.device_id_or_new()?;
    let name = gethostname::gethostname().to_string_lossy().to_string();
    let allowed = cfg.allowed_apps.clone();
    println!("Watching DESKTOP schedules as \"{name}\"… (Ctrl+C to stop)");
    loop {
        // Refresh the access token each iteration (Supabase rotates refresh tokens).
        let mut sb = Supabase::new(&cfg.supabase_url, &cfg.anon_key);
        let rt = match cfg.refresh_token.clone() {
            Some(rt) => rt,
            None => {
                eprintln!("not signed in — run `touchgrass-agent login`");
                return Ok(());
            }
        };
        match sb.refresh(&rt) {
            Ok(s) => {
                cfg.refresh_token = Some(s.refresh_token);
                let _ = cfg.save();
                sb.set_access(s.access_token);
            }
            Err(e) => {
                eprintln!("auth refresh failed: {e}");
                sleep(Duration::from_secs(60));
                continue;
            }
        }
        let _ = sb.upsert_device(&device_id, &name);

        let schedules = match sb.list_schedules() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("schedule fetch failed: {e}");
                sleep(Duration::from_secs(60));
                continue;
            }
        };
        let now = Local::now();
        let next = schedules
            .iter()
            .filter(|s| s.enabled && s.target_platforms.iter().any(|p| p.eq_ignore_ascii_case("DESKTOP")))
            .filter_map(|s| next_run(s, now).map(|t| (t, s)))
            .min_by_key(|(t, _)| *t);

        match next {
            None => sleep(Duration::from_secs(300)),
            Some((at, sched)) => {
                let wait = (at - now).num_seconds().max(0) as u64;
                let title = if sched.title.is_empty() { "Focus" } else { &sched.title };
                println!("Next: {} at {}", title, at.format("%a %H:%M"));
                let chunk = wait.min(300);
                sleep(Duration::from_secs(chunk));
                if chunk >= wait {
                    let scfg = SessionConfig {
                        focus_min: sched.focus_block_min.max(1),
                        break_min: sched.break_min.max(0),
                        cycles: sched.cycles.max(1),
                        allowed_apps: allowed.clone(),
                    };
                    if let Err(e) = run_session(&sb, &device_id, &scfg) {
                        eprintln!("session failed: {e}");
                    }
                }
            }
        }
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
