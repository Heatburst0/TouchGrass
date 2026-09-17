# TouchGrass laptop agent (Rust)

A lightweight agent that tracks your **productive time** during focus sessions on
the laptop — input activity (keyboard/mouse *presence*, never contents) while an
allowed app (VS Code, Android Studio, …) is focused — and syncs each session to
Supabase, so it shows up in your phone's history and streaks. It can also
auto-start `DESKTOP`-targeted schedules you create on the phone.

## Prerequisites

- **Rust** (stable): install from [rustup.rs](https://rustup.rs).
- Your Supabase project (the same one the phone uses).

## Supabase one-time tweak (for headless sign-in)

The agent signs in with a **6-digit email code** (no browser). Supabase only puts
that code in the email if the template references it:

- Dashboard → **Authentication → Email Templates → Magic Link** → add a line:
  `Your code: {{ .Token }}`
- This is backward-compatible — the phone's magic *link* still works.

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

## Notes / limits (MVP)

- This is **tracking**, not hard enforcement — it measures productive time and
  flags off-task app switches; it doesn't forcibly close apps (a later option).
- Schedules are matched by local time; keep the machine's clock/timezone correct.
- `cargo build` errors? Paste them — the crate set (reqwest/rustls, device_query,
  active-win-pos-rs) is standard but platform toolchains vary.
