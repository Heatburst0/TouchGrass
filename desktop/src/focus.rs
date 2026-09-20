use crate::supabase::{RemoteDeviceEvent, RemoteFocusSession, Supabase};
use crate::tracker::Tracker;
use anyhow::Result;
use chrono::Utc;
use serde_json::json;
use std::thread::sleep;
use std::time::Duration;

pub struct SessionConfig {
    pub focus_min: i64,
    pub break_min: i64,
    pub cycles: i64,
    pub allowed_apps: Vec<String>,
}

const SAMPLE_SECS: i64 = 5;

/// Runs a focus session, measuring productive time (input activity while an allowed
/// app is focused) and writing telemetry + a final session row to Supabase.
pub fn run_session(sb: &Supabase, device_id: &str, cfg: &SessionConfig) -> Result<()> {
    let allowed: Vec<String> = cfg
        .allowed_apps
        .iter()
        .map(|a| a.to_lowercase())
        .filter(|a| !a.is_empty())
        .collect();
    let mut tracker = Tracker::new();
    let started = Utc::now();
    let mut active_secs: i64 = 0;
    let mut violations: i64 = 0;

    println!(
        "Focus started: {} × {}m focus, {}m breaks. Allowed: {}",
        cfg.cycles,
        cfg.focus_min,
        cfg.break_min,
        cfg.allowed_apps.join(", ")
    );

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
            let app = tracker.active_app();
            let on_task = allowed.iter().any(|a| app.contains(a));
            if active && on_task {
                active_secs += SAMPLE_SECS;
                win_active += SAMPLE_SECS;
            } else {
                win_idle += SAMPLE_SECS;
                if active && !on_task {
                    violations += 1;
                    let shown = if app.is_empty() { "unknown" } else { app.as_str() };
                    println!("  off-task: {}", shown);
                }
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
        config: json!({ "allowed": cfg.allowed_apps }),
    })?;

    println!(
        "Session complete: {}/{} focus min productive · {} off-task switches. Synced.",
        focused_min, planned, violations
    );
    Ok(())
}
