use crate::config::Config;
use crate::focus::{run_session, SessionConfig};
use crate::supabase::{Policy, RemoteSchedule};
use anyhow::Result;
use chrono::{
    DateTime, Datelike, Duration as ChronoDuration, Local, NaiveTime, TimeZone, Utc, Weekday,
};
use chrono_tz::Tz;
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
    let tracker = crate::tracker::Tracker::new();
    let mut seen_apps: HashSet<String> = HashSet::new();

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
                    crate::overlay::banner(
                        "TouchGrass can't reach your account",
                        &format!("{e:#}"),
                        crate::overlay::Tone::Bad,
                    );
                    sleep(Duration::from_secs(60));
                    continue;
                }
            }
        }

        let policy = crate::resolve_policy(&sb, cfg);

        // Passively discover foreground apps so the phone's rules picker fills in
        // even before any session runs.
        let (fg_app, _) = tracker.active_window();
        if !fg_app.is_empty() && seen_apps.insert(fg_app.clone()) {
            let _ = sb.upsert_device_app(&device_id, &fg_app, &fg_app);
        }

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
        let now = Utc::now();
        let schedules = sb.list_schedules().unwrap_or_default();
        let next = schedules
            .iter()
            .filter(|s| {
                s.enabled
                    && s.target_platforms
                        .iter()
                        .any(|p| p.eq_ignore_ascii_case("DESKTOP"))
            })
            .filter_map(|s| next_run_utc(s, now).map(|t| (t, s)))
            .min_by_key(|(t, _)| *t);

        if let Some((at, sched)) = next {
            let wait = (at - Utc::now()).num_seconds().max(0) as u64;
            if wait <= POLL_SECS {
                let title = if sched.title.is_empty() {
                    "Focus".to_string()
                } else {
                    sched.title.clone()
                };
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
                    watch_remote_stop: false,
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
    let block_sites = config
        .get("blockSites")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    SessionConfig {
        focus_min: get("focusBlockMin", 25).max(1),
        break_min: get("breakMin", 5).max(0),
        cycles: get("cycles", 1).max(1),
        allowed_apps: p.allowed_apps.clone(),
        blocked_apps: p.blocked_apps.clone(),
        force_quit_apps: p.force_quit_apps.clone(),
        blocked_sites: p.blocked_sites.clone(),
        block_sites,
        watch_remote_stop: true, // phone owns this live session; stop when it clears active_sessions
        broadcast: false,
    }
}

/// Find the next occurrence in the stored zone, falling back to the device zone.
fn next_run_utc(sched: &RemoteSchedule, now: DateTime<Utc>) -> Option<DateTime<Utc>> {
    match sched.timezone.parse::<Tz>() {
        Ok(zone) => next_run_in_zone(sched, now, zone),
        Err(_) => next_run_in_zone(sched, now, Local),
    }
}

/// Search local calendar dates, skipping DST gaps and choosing the earlier fold.
fn next_run_in_zone<T: TimeZone>(
    sched: &RemoteSchedule,
    now: DateTime<Utc>,
    zone: T,
) -> Option<DateTime<Utc>> {
    let (h, m) = parse_hhmm(&sched.start_local_time);
    let time = NaiveTime::from_hms_opt(h, m, 0)?;
    let days = parse_days(&sched.recurrence);
    for i in 0..8 {
        let date = now.with_timezone(&zone).date_naive() + ChronoDuration::days(i);
        let runs = match &days {
            None => true,
            Some(set) => set.contains(&date.weekday()),
        };
        if !runs {
            continue;
        }
        if let Some(dt) = zone.from_local_datetime(&date.and_time(time)).earliest() {
            let utc = dt.with_timezone(&Utc);
            if utc > now {
                return Some(utc);
            }
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
    let t = recurrence
        .get("type")
        .and_then(|v| v.as_str())
        .unwrap_or("DAILY");
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn utc(value: &str) -> DateTime<Utc> {
        DateTime::parse_from_rfc3339(value)
            .unwrap()
            .with_timezone(&Utc)
    }

    fn schedule(time: &str, zone: &str) -> RemoteSchedule {
        serde_json::from_value(json!({
            "id": "test",
            "start_local_time": time,
            "timezone": zone,
            "recurrence": { "type": "DAILY" }
        }))
        .unwrap()
    }

    #[test]
    fn parses_hhmm_with_defaults() {
        assert_eq!(parse_hhmm("14:35"), (14, 35));
        assert_eq!(parse_hhmm("09:00"), (9, 0));
        assert_eq!(parse_hhmm("junk"), (9, 0));
        assert_eq!(parse_hhmm(""), (9, 0));
    }

    #[test]
    fn parses_daily_and_weekly_days_ignoring_unknown_names() {
        assert_eq!(parse_days(&json!({ "type": "DAILY" })), None);
        assert_eq!(
            parse_days(&json!({ "type": "WEEKLY", "days": ["MONDAY", "friday", "junk", 3] })),
            Some(HashSet::from([Weekday::Mon, Weekday::Fri]))
        );
    }

    #[test]
    fn same_day_later_time() {
        assert_eq!(
            next_run_utc(&schedule("09:00", "UTC"), utc("2026-10-25T08:00:00Z")),
            Some(utc("2026-10-25T09:00:00Z"))
        );
    }

    #[test]
    fn passed_time_runs_next_day() {
        for now in ["2026-10-25T10:00:00Z", "2026-10-25T09:00:00Z"] {
            assert_eq!(
                next_run_utc(&schedule("09:00", "UTC"), utc(now)),
                Some(utc("2026-10-26T09:00:00Z"))
            );
        }
    }

    #[test]
    fn weekly_picks_next_matching_weekday() {
        let mut sched = schedule("09:00", "UTC");
        sched.recurrence = json!({ "type": "WEEKLY", "days": ["MONDAY"] });
        assert_eq!(
            next_run_utc(&sched, utc("2026-10-25T10:00:00Z")),
            Some(utc("2026-10-26T09:00:00Z"))
        );
        assert_eq!(
            next_run_utc(&sched, utc("2026-10-26T10:00:00Z")),
            Some(utc("2026-11-02T09:00:00Z"))
        );
    }

    #[test]
    fn stored_timezone_changes_utc_time() {
        let now = utc("2026-10-25T00:00:00Z");
        assert_eq!(
            next_run_utc(&schedule("09:00", "Asia/Kolkata"), now),
            Some(utc("2026-10-25T03:30:00Z"))
        );
        assert_eq!(
            next_run_utc(&schedule("09:00", "UTC"), now),
            Some(utc("2026-10-25T09:00:00Z"))
        );
    }

    #[test]
    fn weekday_uses_schedule_zone() {
        let mut sched = schedule("09:00", "Asia/Kolkata");
        sched.recurrence = json!({ "type": "WEEKLY", "days": ["MONDAY"] });
        // It is already Monday in Kolkata while UTC is still Sunday.
        assert_eq!(
            next_run_utc(&sched, utc("2026-10-25T23:00:00Z")),
            Some(utc("2026-10-26T03:30:00Z"))
        );
    }

    #[test]
    fn invalid_or_missing_zone_falls_back_to_local() {
        let now = utc("2026-10-25T00:00:00Z");
        for zone in ["not/a-zone", ""] {
            let sched = schedule("09:00", zone);
            assert_eq!(
                next_run_utc(&sched, now),
                next_run_in_zone(&sched, now, Local)
            );
            assert!(next_run_utc(&sched, now).is_some());
        }
        let missing: RemoteSchedule =
            serde_json::from_value(json!({"id": "test", "start_local_time": "09:00"})).unwrap();
        assert!(missing.timezone.is_empty());
        assert_eq!(
            next_run_utc(&missing, now),
            next_run_in_zone(&missing, now, Local)
        );
    }

    #[test]
    fn spring_forward_gap_skips_to_next_valid_day() {
        assert_eq!(
            next_run_utc(
                &schedule("02:30", "America/New_York"),
                utc("2026-03-08T05:00:00Z")
            ),
            Some(utc("2026-03-09T06:30:00Z"))
        );
    }

    #[test]
    fn fall_back_uses_earliest_occurrence() {
        let sched = schedule("01:30", "America/New_York");
        assert_eq!(
            next_run_utc(&sched, utc("2026-11-01T04:00:00Z")),
            Some(utc("2026-11-01T05:30:00Z"))
        );
        assert_eq!(
            next_run_utc(&sched, utc("2026-11-01T06:00:00Z")),
            Some(utc("2026-11-02T06:30:00Z"))
        );
    }

    #[test]
    fn session_config_defaults_and_policy() {
        let policy = Policy {
            allowed_apps: vec!["editor".into()],
            blocked_apps: vec!["game".into()],
            force_quit_apps: vec!["chat".into()],
            blocked_sites: vec!["example.com".into()],
        };
        let cfg = session_from_config(&json!({}), &policy);
        assert_eq!((cfg.focus_min, cfg.break_min, cfg.cycles), (25, 5, 1));
        assert!(!cfg.block_sites);
        assert!(cfg.watch_remote_stop);
        assert!(!cfg.broadcast);
        assert_eq!(cfg.allowed_apps, policy.allowed_apps);
        assert_eq!(cfg.blocked_apps, policy.blocked_apps);
        assert_eq!(cfg.force_quit_apps, policy.force_quit_apps);
        assert_eq!(cfg.blocked_sites, policy.blocked_sites);
    }

    #[test]
    fn session_config_clamps_nonpositive_values() {
        for value in [0, -5] {
            let cfg = session_from_config(
                &json!({ "focusBlockMin": value, "breakMin": value, "cycles": value }),
                &Policy::default(),
            );
            assert_eq!((cfg.focus_min, cfg.break_min, cfg.cycles), (1, 0, 1));
            assert!(cfg.watch_remote_stop);
        }
    }

    #[test]
    fn session_config_honors_site_block_gate() {
        for block_sites in [true, false] {
            let cfg = session_from_config(&json!({"blockSites": block_sites}), &Policy::default());
            assert_eq!(cfg.block_sites, block_sites);
            assert!(cfg.watch_remote_stop);
        }
    }
}
