# TouchGrass laptop agent (Rust)

A lightweight agent that tracks your **productive time** during focus sessions on
the laptop — input activity (keyboard/mouse *presence*, never contents) while an
allowed app (VS Code, Android Studio, …) is focused — and syncs each session to
Supabase, so it shows up in your phone's history and streaks. It can also
auto-start `DESKTOP`-targeted schedules you create on the phone.

While `run` watches, it also tracks active app/project seconds for focus quotas
and starts auto-focus sessions for enabled work targets after sustained activity.
Auto-focus waits through the first minute after startup, tolerates brief gaps,
and requires 15 minutes away before a target can trigger again. Usage also counts
during focus sessions and breaks, and syncs about once a minute. Failed uploads
are retried in memory (up to 500 app/project/day entries); restarting loses unsent
usage. Apply the work-targets migration before using these features.

Phone focus starts/stops and rule, schedule, and work-target changes wake the
agent immediately via Supabase Realtime. Enable the four tables in the project's
Realtime publication with access allowed by RLS. Polling remains the fallback
(20 seconds for sessions/rules/schedules, 15 seconds for remote stops, and
60 seconds for work targets) when Realtime is unavailable.

## Prerequisites

- **Rust** (stable): install from [rustup.rs](https://rustup.rs).
- Your Supabase project (the same one the phone uses).

## Sign-in

No Supabase changes needed. `login` sends you the normal sign-in email; you copy
the **"Sign in" link** from it and paste it into the agent, which pulls the token
out and completes sign-in headlessly.

(Optional: if you set up custom SMTP and add `Your code: {{ .Token }}` to the
Magic Link email template, you can paste the 6-digit code instead of the link.)

## Build

```bash
cd desktop
cargo build --release
# binary at target/release/touchgrass-agent(.exe)
```

## Configure

Copy `config.example.toml` to your OS config dir as `touchgrass/agent.toml`
(paths in that file) and set `supabase_url` + `anon_key` (the same public values
from the phone's `local.properties`). Optionally edit `allowed_apps`.

## Use

```bash
touchgrass-agent login            # email + 6-digit code, registers this device
touchgrass-agent status           # check sign-in / config
touchgrass-agent focus --minutes 25            # a single 25-min session now
touchgrass-agent focus --cycles 4 --minutes 25 --break 5   # a Pomodoro
touchgrass-agent run              # watch schedules and auto-start DESKTOP ones
```

While a session runs it prints per-cycle progress, warns when you're off-task
(active window not in `allowed_apps`), and on completion writes a
`focus_sessions` row (productive minutes) + `INPUT_ACTIVITY` telemetry. Open the
phone → Focus tab and the laptop session appears in **Your record**.

## Privacy

The agent records **whether** input happened and the **active app's name** — never
keystrokes, clipboard, or screen contents.
IDE window titles are parsed locally: only the parsed project name is uploaded,
never window titles or file names. Other apps have no project attached.

## Notes / limits (MVP)

- This is **tracking**, not hard enforcement — it measures productive time and
  flags off-task app switches; it doesn't forcibly close apps (a later option).
- Schedules are matched by local time; keep the machine's clock/timezone correct.
- `cargo build` errors? Paste them — the crate set (reqwest/rustls, device_query,
  active-win-pos-rs) is standard but platform toolchains vary.
