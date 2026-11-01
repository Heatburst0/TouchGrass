use crate::overlay::{self, Tone};
use crate::realtime::{self, Event};
use crate::supabase::{RemoteDeviceEvent, RemoteFocusSession, Supabase};
use crate::tracker::Tracker;
use crate::{enforce, hosts, notify, project, usage};
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
    pub label: Option<String>,
    pub source: Option<String>,
}

const SAMPLE_SECS: i64 = 5;
const REMOTE_CHECK_SECS: i64 = 15; // how often to re-check the shared active_sessions row
const BLOCK_NOTICE_COOLDOWN_SECS: u64 = 60; // don't re-announce the same blocked app more often than this
const MIN_PRODUCTIVE_FRACTION: f64 = 0.2;

/// Shared session metadata, including auto-focus attribution when supplied.
fn broadcast_config(cfg: &SessionConfig) -> serde_json::Value {
    let mut config = json!({
        "focusBlockMin": cfg.focus_min, "breakMin": cfg.break_min,
        "cycles": cfg.cycles, "blockSites": cfg.block_sites,
        "blockPhone": cfg.broadcast,
    });
    if let Some(label) = &cfg.label {
        config["label"] = json!(label);
    }
    if let Some(source) = &cfg.source {
        config["source"] = json!(source);
    }
    config
}

/// The other device (same start time) can finish a few seconds before us and clear
/// the shared row; a stop that close to the end is a completed session, not an early one.
fn finished_with_other_device(remaining_secs: i64) -> bool {
    remaining_secs <= NATURAL_END_SLACK_SECS
}

const NATURAL_END_SLACK_SECS: i64 = 120;

/// A local-only auto session must not stop on a stale inactive shared row.
fn remote_stop_requested(active: bool, observed_active: &mut bool) -> bool {
    if active {
        *observed_active = true;
        false
    } else {
        *observed_active
    }
}

/// Interrupt a sample wait for remote stops without advancing session time.
/// Other invalidations are held until the watch loop resumes.
struct RemoteStop {
    last_check: Instant,
    observed_active: bool,
    deferred: Vec<Event>,
}

impl RemoteStop {
    fn wait_sample(&mut self, sb: &Supabase, enabled: bool) -> bool {
        let interval = Duration::from_secs(SAMPLE_SECS as u64);
        if !enabled {
            sleep(interval);
            return false;
        }
        let started = Instant::now();
        loop {
            let timeout = interval.saturating_sub(started.elapsed()).min(
                Duration::from_secs(REMOTE_CHECK_SECS as u64)
                    .saturating_sub(self.last_check.elapsed()),
            );
            let events = realtime::wait(timeout);
            let changed = events.contains(&Event::ActiveSession);
            for event in events {
                if event != Event::ActiveSession && !self.deferred.contains(&event) {
                    self.deferred.push(event);
                }
            }
            if changed || self.last_check.elapsed() >= Duration::from_secs(REMOTE_CHECK_SECS as u64)
            {
                self.last_check = Instant::now();
                if let Ok(Some(session)) = sb.get_active_session() {
                    if remote_stop_requested(session.active, &mut self.observed_active) {
                        println!("Focus session ended from another device — stopping.");
                        return true;
                    }
                }
            }
            if started.elapsed() >= interval {
                return false;
            }
        }
    }
}

impl Drop for RemoteStop {
    fn drop(&mut self) {
        // Reconcile the shared row and anything changed while this session ran.
        realtime::publish(self.deferred.drain(..).chain([Event::ActiveSession]));
    }
}

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
        let _ = sb.set_active_session(&started.to_rfc3339(), device_id, broadcast_config(cfg));
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
    let mut remote = RemoteStop {
        last_check: Instant::now(),
        observed_active: cfg.broadcast || cfg.source.as_deref() != Some("AUTO"),
        deferred: Vec::new(),
    };
    let mut last_usage_flush = Instant::now();

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
            if remote.wait_sample(sb, cfg.watch_remote_stop) {
                let remaining = (block_secs - elapsed)
                    + (cfg.cycles - cycle) * (cfg.focus_min + cfg.break_min) * 60;
                ran_full_time = finished_with_other_device(remaining);
                break 'session;
            }
            elapsed += SAMPLE_SECS;
            let active = tracker.sample_active();
            let window = tracker.active_window_info();
            let (app, pid) = (window.app, window.pid);
            if active {
                let project = project::parse_project(&app, &window.title);
                usage::record(&app, project.as_deref(), SAMPLE_SECS);
            }
            if last_usage_flush.elapsed() >= Duration::from_secs(60) {
                usage::flush(sb, device_id);
                last_usage_flush = Instant::now();
            }
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
            for tick in 0..(cfg.break_min * 60 / SAMPLE_SECS) {
                if remote.wait_sample(sb, cfg.watch_remote_stop) {
                    let remaining = cfg.break_min * 60 - tick * SAMPLE_SECS
                        + (cfg.cycles - cycle) * cfg.focus_min * 60
                        + (cfg.cycles - cycle - 1) * cfg.break_min * 60;
                    ran_full_time = finished_with_other_device(remaining);
                    break 'session;
                }
                let active = tracker.sample_active();
                let window = tracker.active_window_info();
                if active {
                    let project = project::parse_project(&window.app, &window.title);
                    usage::record(&window.app, project.as_deref(), SAMPLE_SECS);
                }
                if last_usage_flush.elapsed() >= Duration::from_secs(60) {
                    usage::flush(sb, device_id);
                    last_usage_flush = Instant::now();
                }
            }
        }
    }

    usage::flush(sb, device_id);
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
    fn auto_broadcast_preserves_session_options_and_attribution() {
        let cfg = SessionConfig {
            focus_min: 50,
            break_min: 0,
            cycles: 1,
            allowed_apps: vec![],
            blocked_apps: vec![],
            force_quit_apps: vec![],
            blocked_sites: vec![],
            block_sites: true,
            watch_remote_stop: true,
            broadcast: true,
            label: Some("Build TouchGrass".into()),
            source: Some("AUTO".into()),
        };
        assert_eq!(
            broadcast_config(&cfg),
            json!({
                "focusBlockMin": 50, "breakMin": 0, "cycles": 1,
                "blockSites": true, "blockPhone": true,
                "source": "AUTO", "label": "Build TouchGrass",
            })
        );
    }

    #[test]
    fn local_auto_ignores_stale_inactive_row_until_shared_activity() {
        let mut observed = false;
        assert!(!remote_stop_requested(false, &mut observed));
        assert!(!remote_stop_requested(true, &mut observed));
        assert!(remote_stop_requested(false, &mut observed));
        // Joined and broadcast sessions already know the shared session exists.
        assert!(remote_stop_requested(false, &mut true));
    }

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
    fn remote_stop_near_the_end_counts_as_finished() {
        assert!(finished_with_other_device(0));
        assert!(finished_with_other_device(120));
        assert!(!finished_with_other_device(121));
        assert!(!finished_with_other_device(20 * 60));
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
