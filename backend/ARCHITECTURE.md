# TouchGrass backend architecture

The backend is the **product spine**, not a focus-session feature. It is a Supabase
project (Postgres + Auth + Realtime + RLS) that any current or future TouchGrass
surface — phone, laptop agent, a web app later — reads and writes.

## Why this shape

- **General, envelope-based schema.** `goals` mirrors the phone's goal+verifier
  model (a `type` plus JSONB `config`/`state`), and `device_events` is a generic
  telemetry envelope (`kind` + JSONB `payload`). New feature types (language
  learning, chess, a new tracker) need **no schema migration** — the same reason
  the phone uses one unified `goals` table.
- **User-scoped + RLS everywhere.** Every row has `user_id default auth.uid()` and
  a `user_id = auth.uid()` policy, so a device can only ever see its own account's
  data. Auth is Supabase email magic-link / OAuth.
- **Sync-shaped rows.** `updated_at`, `deleted` (tombstone), and `origin_device_id`
  on every syncable table make last-write-wins pull/push trivial and map 1:1 to the
  phone's existing `SyncRecord` / `SyncSource` seam (`app/.../core/sync`).
- **Realtime fan-out.** `goals`, `focus_sessions`, and `focus_schedules` are in the
  `supabase_realtime` publication; devices subscribe and react live (a schedule
  edited on the phone reaches the laptop immediately). RLS still filters each
  subscriber.

## Tables

| Table | Purpose |
|-------|---------|
| `devices` | Each phone / laptop agent on the account (platform, name, last seen). |
| `goals` | The unified goal spine (type + JSONB config/state), same model as the phone. |
| `focus_sessions` | Cross-device focus history. `focused_min` = productive minutes (time-based on phone, input-activity-based on laptop); `active_min` = input-active minutes (desktop). |
| `focus_schedules` | Recurring / scheduled sessions. **Server owns the definition; each device computes its own next run and self-triggers** (phone AlarmManager, laptop timer) — works offline, no server cron to start a session. |
| `device_events` | Generic telemetry envelope. The Rust agent writes `INPUT_ACTIVITY` samples (active/idle seconds + focused app) here, linked to a session. |
| `points_ledger` | Append-only economy (balance = `sum(delta)`), mirroring the phone ledger. `points_balance()` returns the caller's balance. |

## Data flow: a laptop focus session

1. A `focus_schedules` row (created on any device) is realtime-synced to the laptop.
2. The laptop agent computes the next run locally and self-starts at the time.
3. During the session the agent samples input activity + the active window, allowing
   only the configured apps (e.g. VS Code, Android Studio), and periodically writes
   `device_events` (`kind = INPUT_ACTIVITY`) — **activity presence only, never
   keystroke content**.
4. On completion it writes a `focus_sessions` row with `focused_min` / `active_min`
   from real activity, and (if the session met a `FOCUS_SESSION` goal) a
   `points_ledger` entry.
5. Realtime pushes the new session to the phone, so history + streaks stay unified.

## Privacy

The desktop agent records **which app is focused and whether input is happening**,
to measure productive time. It never captures key contents, clipboard, or screen.

## What lives where

- `supabase/migrations/` — schema, RLS, realtime, functions (source of truth).
- `supabase/config.toml` — CLI/local config. Secrets live in the dashboard / `.env`
  (git-ignored), never in the repo.
- Phone client: `app/.../core/sync` (seam) + a Supabase data layer (Phase 2).
- Laptop agent: `desktop/` (Rust, Phase 3).

## Roadmap

- **Phase 1 (done):** schema + RLS + realtime + points function + setup docs.
- **Phase 2:** phone — supabase-kt auth, device register, session sync, schedule
  subscribe + AlarmManager auto-start.
- **Phase 3:** Rust agent — auth, input-activity + active-window tracking,
  app-allowlist, productive-minute reporting, schedule auto-start.
- **Later:** optional `pg_cron` / edge functions for server-driven reminders and
  cross-device aggregation; a web dashboard.
