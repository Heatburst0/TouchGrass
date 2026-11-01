use crate::supabase::WorkTarget;
use std::collections::HashMap;

pub const GAP_TOLERANCE_SECS: i64 = 90;
pub const REARM_GAP_SECS: i64 = 15 * 60;
pub const RETRY_AFTER_SECS: i64 = 60;

#[derive(Default)]
struct Streak {
    active: i64,
    gap: i64,
    disarmed: bool,
}

/// Pure auto-focus decisions; only observed samples count, never session time.
#[derive(Default)]
pub struct AutoFocus {
    streaks: HashMap<String, Streak>,
    last_observed: Option<i64>,
}

impl AutoFocus {
    /// Update every target, then fire at most one in the supplied priority order.
    pub fn observe(
        &mut self,
        now_secs: i64,
        sample_secs: i64,
        active: bool,
        app: &str,
        project: Option<&str>,
        targets: &[WorkTarget],
    ) -> Option<WorkTarget> {
        let secs = sample_secs.max(0).min(
            self.last_observed
                .map(|last| now_secs.saturating_sub(last).max(0))
                .unwrap_or(i64::MAX),
        );
        self.last_observed = Some(now_secs);
        self.streaks.retain(|id, _| {
            targets
                .iter()
                .any(|t| &t.id == id && t.enabled && t.auto_focus)
        });
        let mut fired = None;
        for target in targets.iter().filter(|t| t.enabled && t.auto_focus) {
            let streak = self.streaks.entry(target.id.clone()).or_default();
            if active && matches_target(target, app, project) {
                streak.gap = 0;
                if !streak.disarmed {
                    streak.active = streak.active.saturating_add(secs);
                }
            } else {
                streak.gap = streak.gap.saturating_add(secs);
                if streak.gap > GAP_TOLERANCE_SECS {
                    streak.active = 0;
                }
                if streak.gap >= REARM_GAP_SECS {
                    streak.disarmed = false;
                }
            }
            if fired.is_none()
                && !streak.disarmed
                && active
                && matches_target(target, app, project)
                && streak.active >= target.auto_focus_after_min.max(1).saturating_mul(60)
            {
                streak.disarmed = true;
                fired = Some(target.clone());
            }
        }
        fired
    }

    /// The start couldn't happen (e.g. the live-session check failed or another
    /// session is running): stay armed and try again after another minute of work.
    pub fn defer(&mut self, target_id: &str) {
        if let Some(streak) = self.streaks.get_mut(target_id) {
            streak.disarmed = false;
            streak.active = streak.active.saturating_sub(RETRY_AFTER_SECS);
        }
    }

    /// Sessions are unobserved here: require a fresh absence before any rearm.
    pub fn session_finished(&mut self, now_secs: i64) {
        self.last_observed = Some(now_secs);
        for streak in self.streaks.values_mut() {
            streak.active = 0;
            streak.gap = 0;
            streak.disarmed = true;
        }
    }
}

fn matches_target(target: &WorkTarget, app: &str, project: Option<&str>) -> bool {
    let wanted = target.app_name.trim().to_lowercase();
    !wanted.is_empty()
        && app.to_lowercase().contains(&wanted)
        && target
            .project
            .as_deref()
            .filter(|p| !p.trim().is_empty())
            .is_none_or(|wanted| project.is_some_and(|p| p.eq_ignore_ascii_case(wanted)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn target() -> WorkTarget {
        WorkTarget {
            id: "code".into(),
            app_name: "CODE".into(),
            auto_focus: true,
            auto_focus_after_min: 1,
            ..WorkTarget::default()
        }
    }

    fn sample(state: &mut AutoFocus, now: i64, secs: i64, active: bool, t: &WorkTarget) -> bool {
        state
            .observe(now, secs, active, "code.exe", None, &[t.clone()])
            .is_some()
    }

    #[test]
    fn deferred_start_retries_after_another_minute_of_work() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(sample(&mut s, 60, 60, true, &t)); // fired, but the start failed…
        s.defer(&t.id);
        assert!(!sample(&mut s, 65, 5, true, &t)); // …not immediately again
        assert!(sample(&mut s, 120, 55, true, &t)); // one more minute of work → retry
    }

    #[test]
    fn fires_at_threshold_once() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(!sample(&mut s, 55, 55, true, &t));
        assert!(sample(&mut s, 60, 5, true, &t));
        assert!(!sample(&mut s, 120, 60, true, &t));
    }

    #[test]
    fn short_gaps_tolerated_long_gaps_reset() {
        for (gap, fires) in [(90, true), (95, false)] {
            let mut s = AutoFocus::default();
            let t = target();
            assert!(!sample(&mut s, 55, 55, true, &t));
            assert!(!sample(&mut s, 55 + gap, gap, false, &t));
            assert_eq!(sample(&mut s, 60 + gap, 5, true, &t), fires);
        }
    }

    #[test]
    fn active_work_in_another_app_resets_the_streak() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(!sample(&mut s, 55, 55, true, &t));
        assert!(s
            .observe(150, 95, true, "browser", None, &[t.clone()])
            .is_none());
        assert!(!sample(&mut s, 155, 5, true, &t));
        assert!(sample(&mut s, 210, 55, true, &t));
    }

    #[test]
    fn firing_alone_disarms_until_rearm_gap() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(sample(&mut s, 60, 60, true, &t));
        assert!(!sample(&mut s, 955, 895, false, &t));
        assert!(!sample(&mut s, 1015, 60, true, &t));
        assert!(!sample(&mut s, 1915, 900, false, &t));
        assert!(sample(&mut s, 1975, 60, true, &t));
    }

    #[test]
    fn rearm_requires_continuous_away_gap_after_session() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(sample(&mut s, 60, 60, true, &t));
        s.session_finished(3660);
        assert!(!sample(&mut s, 3720, 60, true, &t));
        assert!(!sample(&mut s, 4615, 895, false, &t));
        assert!(!sample(&mut s, 4620, 5, true, &t));
        assert!(!sample(&mut s, 5520, 900, false, &t));
        assert!(sample(&mut s, 5580, 60, true, &t));
    }

    #[test]
    fn only_observed_seconds_count_and_duplicate_samples_do_not() {
        let mut s = AutoFocus::default();
        let t = target();
        assert!(!sample(&mut s, 5, 5, true, &t));
        assert!(!sample(&mut s, 5000, 5, true, &t));
        assert!(!sample(&mut s, 5000, 60, true, &t));
        assert_eq!(s.streaks[&t.id].active, 10);
    }

    #[test]
    fn project_match_is_case_insensitive_and_optional() {
        let mut t = target();
        t.project = Some("TouchGrass".into());
        assert!(matches_target(&t, "Code.exe", Some("touchgrass")));
        assert!(!matches_target(&t, "code", Some("other")));
        assert!(!matches_target(&t, "code", None));
        assert!(!matches_target(&t, "browser", Some("TouchGrass")));
        let mut s = AutoFocus::default();
        assert!(s
            .observe(60, 60, true, "code", Some("other"), &[t.clone()])
            .is_none());
        t.project = Some("".into());
        assert!(matches_target(&t, "code", None));
    }

    #[test]
    fn disabled_targets_never_fire_and_only_one_fires_per_call() {
        for (enabled, auto_focus) in [(false, true), (true, false)] {
            let t = WorkTarget {
                enabled,
                auto_focus,
                ..target()
            };
            assert!(!sample(&mut AutoFocus::default(), 60, 60, true, &t));
        }
        let t = target();
        let other = WorkTarget {
            id: "other".into(),
            ..target()
        };
        let mut s = AutoFocus::default();
        assert_eq!(
            s.observe(60, 60, true, "code", None, &[t.clone(), other.clone()])
                .unwrap()
                .id,
            t.id
        );
        assert!(s.streaks[&t.id].disarmed);
        assert!(!s.streaks[&other.id].disarmed);
    }
}
