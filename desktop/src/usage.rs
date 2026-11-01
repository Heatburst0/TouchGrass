use crate::supabase::Supabase;
use chrono::{Local, NaiveDate};
use std::collections::BTreeMap;
use std::sync::{Mutex, OnceLock};

const MAX_PENDING: usize = 500;
const MAX_INCREMENT: i64 = 3600;
type Key = (NaiveDate, String, String);

/// Ordered by local day so eviction always drops the oldest days first.
#[derive(Default)]
struct Pending {
    entries: BTreeMap<Key, i64>,
}

impl Pending {
    fn record_on(&mut self, day: NaiveDate, app: &str, project: Option<&str>, secs: i64) {
        if secs <= 0 || app.trim().is_empty() {
            return;
        }
        let key = (day, app.to_lowercase(), project.unwrap_or("").to_string());
        let total = self.entries.entry(key).or_default();
        *total = total.saturating_add(secs);
        while self.entries.len() > MAX_PENDING {
            self.entries.pop_first();
        }
    }

    /// Successful chunks are removed; failures retain only the unsent remainder.
    fn flush_with(&mut self, mut send: impl FnMut(&Key, i64) -> bool) {
        self.entries.retain(|key, secs| {
            while *secs > 0 {
                let chunk = (*secs).min(MAX_INCREMENT);
                if !send(key, chunk) {
                    return true;
                }
                *secs -= chunk;
            }
            false
        });
    }
}

fn pending() -> &'static Mutex<Pending> {
    static PENDING: OnceLock<Mutex<Pending>> = OnceLock::new();
    PENDING.get_or_init(|| Mutex::new(Pending::default()))
}

/// Record active seconds on the device's local day, shared across session/watch loops.
pub fn record(app: &str, project: Option<&str>, secs: i64) {
    pending()
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .record_on(Local::now().date_naive(), app, project, secs);
}

/// Upload pending increments without holding the recording mutex during I/O.
pub fn flush(sb: &Supabase, device_id: &str) {
    let mut batch = {
        let mut pending = pending().lock().unwrap_or_else(|e| e.into_inner());
        std::mem::take(&mut *pending)
    };
    batch.flush_with(|(day, app, project), secs| {
        match sb.add_app_usage(device_id, *day, app, project, secs) {
            Ok(()) => true,
            Err(e) => {
                eprintln!("usage sync: {e}");
                false
            }
        }
    });
    let mut pending = pending().lock().unwrap_or_else(|e| e.into_inner());
    for ((day, app, project), secs) in batch.entries {
        pending.record_on(day, &app, Some(&project), secs);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day() -> NaiveDate {
        NaiveDate::from_ymd_opt(2026, 11, 1).unwrap()
    }

    #[test]
    fn accumulates_and_separates_app_project_and_day() {
        let mut p = Pending::default();
        p.record_on(day(), "Code", None, 5);
        p.record_on(day(), "code", Some(""), 10);
        p.record_on(day(), "code", Some("touchgrass"), 5);
        p.record_on(day(), "idea", None, 5);
        p.record_on(day().succ_opt().unwrap(), "code", None, 5);
        assert_eq!(p.entries.len(), 4);
        assert_eq!(p.entries[&(day(), "code".into(), "".into())], 15);
        p.record_on(day(), "", None, 5);
        p.record_on(day(), "code", None, -5);
        assert_eq!(p.entries.values().sum::<i64>(), 30);
    }

    #[test]
    fn cap_drops_oldest_days_even_when_inserted_last() {
        let mut p = Pending::default();
        for i in 0..MAX_PENDING {
            p.record_on(day(), &format!("app{i}"), None, 5);
        }
        p.record_on(day().pred_opt().unwrap(), "old", None, 5);
        assert_eq!(p.entries.len(), MAX_PENDING);
        assert!(p.entries.keys().all(|key| key.0 == day()));
        p.record_on(day().succ_opt().unwrap(), "new", None, 5);
        assert_eq!(p.entries.len(), MAX_PENDING);
        assert!(p.entries.keys().any(|key| key.1 == "new"));
    }

    #[test]
    fn flush_retains_failures_and_chunks_rpc_increments() {
        let mut p = Pending::default();
        p.record_on(day(), "code", None, 7205);
        p.record_on(day(), "idea", None, 5);
        let mut sent = Vec::new();
        p.flush_with(|key, secs| {
            if key.1 == "code" && !sent.is_empty() {
                return false;
            }
            sent.push(secs);
            true
        });
        assert_eq!(sent, vec![3600, 5]);
        assert_eq!(p.entries.values().copied().collect::<Vec<_>>(), vec![3605]);
        sent.clear();
        p.flush_with(|_, secs| {
            sent.push(secs);
            true
        });
        assert_eq!(sent, vec![3600, 5]);
        assert!(p.entries.is_empty());
    }
}
