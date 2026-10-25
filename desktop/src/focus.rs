use crate::overlay::{self, Tone};
use crate::supabase::{RemoteDeviceEvent, RemoteFocusSession, Supabase};
use crate::tracker::Tracker;
use crate::{enforce, hosts, notify};
use anyhow::Result;
use chrono::Utc;
use serde_json::json;
use std::collections::{HashMap, HashSet};
use std::thread::sleep;
use std::time::{Duration, Instant};

pub struct SessionConfig {
    pub focus_min: i64,
    pub break_min: i64,
    pub cycles: i64,
    pub allowed_apps: Vec<String>,
    pub blocked_apps: Vec<String>,
    pub force_quit_apps: Vec<String>,
    pub blocked_sites: Vec<String>,
    pub block_sites: bool, // per-session gate: only null-route sites when the session asked for it
    pub watch_remote_stop: bool, // abort if the shared active_sessions row is cleared (e.g. phone ended it)
    pub broadcast: bool,         // upsert active_sessions so other devices start too
}

const SAMPLE_SECS: i64 = 5;
const REMOTE_CHECK_SECS: i64 = 15; // how often to re-check the shared active_sessions row
const BLOCK_NOTICE_COOLDOWN_SECS: u64 = 60; // don't re-announce the same blocked app more often than this
const MIN_PRODUCTIVE_FRACTION: f64 = 0.2;

/// A full session must include a minimum amount of productive focus time.
fn session_outcome(
    ran_full_time: bool,
    productive_secs: i64,
    planned_focus_secs: i64,
) -> &'static str {
    let enough = planned_focus_secs <= 0
        || productive_secs >= (planned_focus_secs as f64 * MIN_PRODUCTIVE_FRACTION).ceil() as i64;
    if ran_full_time && enough {
        "COMPLETED"
    } else {
        "ENDED_EARLY"
    }
}

/// Whether the blocked-app announcement cooldown has elapsed.
fn should_announce(last: Option<Instant>, now: Instant) -> bool {
    last.is_none_or(|t| now.duration_since(t).as_secs() >= BLOCK_NOTICE_COOLDOWN_SECS)
}

/// Clears the hosts-file site block and hides the on-screen timer when dropped,
/// so an early return or a panic never leaves sites null-routed or a stale HUD.
struct SessionGuard;
impl Drop for SessionGuard {
    fn drop(&mut self) {
        hosts::clear();
        overlay::clear_hud();
    }
}

/// Announce that an app was blocked, at most once per app per cooldown (avoids
/// spam while a blocked app stays in the foreground).
fn block_notice(last: &mut HashMap<String, Instant>, app: &str, action: &str) {
    let now = Instant::now();
    if should_announce(last.get(app).copied(), now) {
        last.insert(app.to_string(), now);
        overlay::banner(
            &format!("{action}: {app}"),
            "Blocked during focus",
            Tone::Bad,
        );
    }
}

/// Runs a focus session, measuring productive time (input activity while an allowed
/// app is focused) and writing telemetry + a final session row to Supabase.
pub fn run_session(sb: &Supabase, device_id: &str, cfg: &SessionConfig) -> Result<()> {
    let allowed: Vec<String> = cfg.allowed_apps.iter().map(|s| s.to_lowercase()).collect();
    let blocked: Vec<String> = cfg.blocked_apps.iter().map(|s| s.to_lowercase()).collect();
    let quit: Vec<String> = cfg
        .force_quit_apps
        .iter()
        .map(|s| s.to_lowercase())
        .collect();

    hosts::clear(); // remove any stale block first
    let _session_guard = SessionGuard; // clears the block + HUD on any exit (incl. panic)
    let sites_blocked = cfg.block_sites && !cfg.blocked_sites.is_empty();
    if sites_blocked {
        if let Err(e) = hosts::apply(&cfg.blocked_sites) {
            eprintln!("site block: {e}");
        }
    }

    let started = Utc::now();
    if cfg.broadcast {
        let _ = sb.set_active_session(&started.to_rfc3339(), device_id,
            json!({ "focusBlockMin": cfg.focus_min, "breakMin": cfg.break_min, "cycles": cfg.cycles, "blockSites": cfg.block_sites }));
    }

    overlay::banner(
        "Focus session started",
        &format!(
            "{}×{}m focus{}{}",
            cfg.cycles,
            cfg.focus_min,
            if cfg.break_min > 0 && cfg.cycles > 1 {
                format!(" · {}m breaks", cfg.break_min)
            } else {
                String::new()
            },
            if sites_blocked {
                " · sites blocked"
            } else {
                ""
            }
        ),
        Tone::Good,
    );
    let mut last_block_notice: HashMap<String, Instant> = HashMap::new();
    let mut off_secs = 0i64; // total off-task seconds, for the live HUD

    let mut tracker = Tracker::new();
    let mut active_secs = 0i64;
    let mut violations = 0i64;
    let mut by_app: HashMap<String, i64> = HashMap::new(); // productive seconds per app
    let mut off_app: HashMap<String, i64> = HashMap::new();
    let mut seen: HashSet<String> = HashSet::new(); // apps already reported this run
    let mut ran_full_time = true;
    let mut remote_since = 0i64; // secs since last active_sessions check

    'session: for cycle in 1..=cfg.cycles {
        println!(
            "[cycle {}/{}] Focus {}m — stay in your allowed apps.",
            cycle, cfg.cycles, cfg.focus_min
        );
        let block_secs = cfg.focus_min * 60;
        overlay::phase(true, cycle, cfg.cycles, block_secs);
        if cycle > 1 {
            overlay::banner(
                &format!("Back to focus · cycle {}/{}", cycle, cfg.cycles),
                &format!("{}m of focus — stay on task.", cfg.focus_min),
                Tone::Good,
            );
        }
        let mut elapsed = 0i64;
        let mut win_active = 0i64;
        let mut win_idle = 0i64;
        while elapsed < block_secs {
            sleep(Duration::from_secs(SAMPLE_SECS as u64));
            elapsed += SAMPLE_SECS;
            // Stop early if the session was ended from another device (e.g. the phone).
            if cfg.watch_remote_stop {
                remote_since += SAMPLE_SECS;
                if remote_since >= REMOTE_CHECK_SECS {
                    remote_since = 0;
                    if let Ok(Some(a)) = sb.get_active_session() {
                        if !a.active {
                            println!("Focus session ended from another device — stopping.");
                            ran_full_time = false;
                            break 'session;
                        }
                    }
                }
            }
            let active = tracker.sample_active();
            let (app, pid) = tracker.active_window();
            if !app.is_empty() && seen.insert(app.clone()) {
                let _ = sb.upsert_device_app(device_id, &app, &app);
            }
            if quit.iter().any(|a| app.contains(a)) {
                enforce::force_quit(pid);
                block_notice(&mut last_block_notice, &app, "Force-quit");
            } else if blocked.iter().any(|a| app.contains(a)) {
                enforce::minimize_foreground();
                block_notice(&mut last_block_notice, &app, "Minimized");
            }
            let on_task = allowed.iter().any(|a| app.contains(a));
            if active {
                win_active += SAMPLE_SECS;
            } else {
                win_idle += SAMPLE_SECS;
            }
            if active && on_task {
                active_secs += SAMPLE_SECS;
                *by_app.entry(app.clone()).or_default() += SAMPLE_SECS;
            } else if active {
                violations += 1;
                off_secs += SAMPLE_SECS;
                *off_app.entry(app.clone()).or_default() += SAMPLE_SECS;
            }
            overlay::stats(active_secs, off_secs);
            if win_active + win_idle >= 60 {
                let _ = sb.insert_device_event(&RemoteDeviceEvent {
                    device_id: device_id.to_string(),
                    session_id: None,
                    kind: "INPUT_ACTIVITY".into(),
                    occurred_at: Utc::now().to_rfc3339(),
                    payload: json!({ "activeSeconds": win_active, "idleSeconds": win_idle, "app": app }),
                });
                win_active = 0;
                win_idle = 0;
            }
        }
        if cycle < cfg.cycles && cfg.break_min > 0 {
            println!("[cycle {}/{}] Break {}m.", cycle, cfg.cycles, cfg.break_min);
            overlay::phase(false, cycle, cfg.cycles, cfg.break_min * 60);
            overlay::banner(
                &format!("Break · {}m", cfg.break_min),
                "Look away from the screen and stretch.",
                Tone::Warn,
            );
            sleep(Duration::from_secs((cfg.break_min * 60) as u64));
        }
    }

    hosts::clear();
    if cfg.broadcast {
        let _ = sb.clear_active_session();
    }

    let ended = Utc::now();
    let focused_min = active_secs / 60;
    let planned = cfg.cycles * cfg.focus_min;
    let outcome = session_outcome(ran_full_time, active_secs, planned * 60);

    sb.insert_focus_session(&RemoteFocusSession {
        id: uuid::Uuid::new_v4().to_string(),
        device_id: device_id.to_string(),
        started_at: started.to_rfc3339(),
        ended_at: ended.to_rfc3339(),
        planned_focus_min: planned,
        focused_min,
        active_min: focused_min,
        cycles: cfg.cycles,
        violations,
        strict: false,
        outcome: outcome.into(),
        config: json!({ "allowed": cfg.allowed_apps, "apps": by_app, "offTask": off_app }),
    })?;

    let off_task_min = off_app.values().sum::<i64>() / 60;
    let title = if outcome == "COMPLETED" {
        "Focus session complete"
    } else {
        "Focus session ended"
    };
    let summary = format!("{focused_min} min productive · {off_task_min} min off-task");
    overlay::clear_hud();
    overlay::banner(title, &summary, Tone::Good);
    notify::toast(title, &summary); // keeps a record in the Action Center

    println!(
        "Session complete: {}/{} focus min productive · {} off-task switches. Synced.",
        focused_min, planned, violations
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outcome_requires_full_time_and_productivity() {
        assert_eq!(session_outcome(true, 60, 100), "COMPLETED");
        assert_eq!(session_outcome(true, 0, 100), "ENDED_EARLY");
        assert_eq!(session_outcome(true, 20, 100), "COMPLETED");
        assert_eq!(session_outcome(true, 20, 101), "ENDED_EARLY");
        assert_eq!(session_outcome(true, 21, 101), "COMPLETED");
        assert_eq!(session_outcome(false, 100, 100), "ENDED_EARLY");
        assert_eq!(session_outcome(true, 0, 0), "COMPLETED");
        assert_eq!(session_outcome(true, 0, -1), "COMPLETED");
        assert_eq!(session_outcome(false, 0, 0), "ENDED_EARLY");
    }

    #[test]
    fn announcements_respect_cooldown() {
        let now = Instant::now();
        assert!(should_announce(None, now));
        assert!(!should_announce(Some(now), now + Duration::from_secs(59)));
        assert!(should_announce(Some(now), now + Duration::from_secs(60)));
        assert!(should_announce(Some(now), now + Duration::from_secs(61)));
    }
}
