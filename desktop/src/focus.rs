use crate::supabase::{RemoteDeviceEvent, RemoteFocusSession, Supabase};
use crate::tracker::Tracker;
use anyhow::Result;
use chrono::Utc;
use serde_json::json;
use std::thread::sleep;
use std::time::Duration;
use crate::{enforce, hosts};
use std::collections::HashMap;

pub struct SessionConfig {
    pub focus_min: i64,
    pub break_min: i64,
    pub cycles: i64,
    pub allowed_apps: Vec<String>,
    pub blocked_apps: Vec<String>,
    pub force_quit_apps: Vec<String>,
    pub blocked_sites: Vec<String>,
    pub block_sites: bool, // per-session gate: only null-route sites when the session asked for it
    pub broadcast: bool, // upsert active_sessions so other devices start too
}


const SAMPLE_SECS: i64 = 5;

/// Runs a focus session, measuring productive time (input activity while an allowed
/// app is focused) and writing telemetry + a final session row to Supabase.
pub fn run_session(sb: &Supabase, device_id: &str, cfg: &SessionConfig) -> Result<()> {
    let allowed:  Vec<String> = cfg.allowed_apps.iter().map(|s| s.to_lowercase()).collect();
    let blocked:  Vec<String> = cfg.blocked_apps.iter().map(|s| s.to_lowercase()).collect();
    let quit:     Vec<String> = cfg.force_quit_apps.iter().map(|s| s.to_lowercase()).collect();

    hosts::clear();                          // remove any stale block first
    let sites_blocked = cfg.block_sites && !cfg.blocked_sites.is_empty();
    if sites_blocked {
        if let Err(e) = hosts::apply(&cfg.blocked_sites) { eprintln!("site block: {e}"); }
    }

    let started = Utc::now();
    if cfg.broadcast {
        let _ = sb.set_active_session(&started.to_rfc3339(), device_id,
            json!({ "focusBlockMin": cfg.focus_min, "breakMin": cfg.break_min, "cycles": cfg.cycles, "blockSites": cfg.block_sites }));
    }

    let mut tracker = Tracker::new();
    let mut active_secs = 0i64;
    let mut violations = 0i64;
    let mut by_app: HashMap<String, i64> = HashMap::new();      // productive seconds per app
    let mut off_app: HashMap<String, i64> = HashMap::new(); 

    for cycle in 1..=cfg.cycles {
        println!("[cycle {}/{}] Focus {}m — stay in your allowed apps.", cycle, cfg.cycles, cfg.focus_min);
        let block_secs = cfg.focus_min * 60;
        let mut elapsed = 0i64;
        let mut win_active = 0i64;
        let mut win_idle = 0i64;
        while elapsed < block_secs {
            sleep(Duration::from_secs(SAMPLE_SECS as u64));
            elapsed += SAMPLE_SECS;
            let active = tracker.sample_active();
            let (app, pid) = tracker.active_window();
            if quit.iter().any(|a| app.contains(a)) {
                enforce::force_quit(pid);
            } else if blocked.iter().any(|a| app.contains(a)) {
                enforce::minimize_foreground();
            }
            let on_task = allowed.iter().any(|a| app.contains(a));
            if active { win_active += SAMPLE_SECS; } else { win_idle += SAMPLE_SECS; }
            if active && on_task {
                active_secs += SAMPLE_SECS;
                *by_app.entry(app.clone()).or_default() += SAMPLE_SECS;
            } else if active {
                violations += 1;
                *off_app.entry(app.clone()).or_default() += SAMPLE_SECS;
            }
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
            sleep(Duration::from_secs((cfg.break_min * 60) as u64));
        }
    }

    hosts::clear();
    if cfg.broadcast { let _ = sb.clear_active_session(); }

    let ended = Utc::now();
    let focused_min = active_secs / 60;
    let planned = cfg.cycles * cfg.focus_min;

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
        outcome: "COMPLETED".into(),
        config: json!({ "allowed": cfg.allowed_apps, "apps": by_app, "offTask": off_app }),
    })?;

    println!(
        "Session complete: {}/{} focus min productive · {} off-task switches. Synced.",
        focused_min, planned, violations
    );
    Ok(())
}
