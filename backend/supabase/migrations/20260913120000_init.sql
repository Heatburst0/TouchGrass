-- TouchGrass backend — initial schema.
--
-- Design: a general productivity spine, not a focus-only backend.
--  * Every row is user-scoped (user_id defaults to auth.uid()) and protected by RLS.
--  * Sync-shaped: updated_at + deleted (tombstone) + origin_device_id so the phone's
--    SyncRecord/SyncSource seam maps 1:1 and last-write-wins is trivial.
--  * Envelope-based: goals carry type-specific data in config/state JSONB, and
--    device_events carry any telemetry in a JSONB payload — new feature types need
--    no migration, mirroring the phone's unified goals table.

-- ---------------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------------
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- devices — every phone / laptop agent that joins the account
-- ---------------------------------------------------------------------------
create table public.devices (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null default auth.uid() references auth.users(id) on delete cascade,
  platform     text not null default 'UNKNOWN'
                 check (platform in ('ANDROID','DESKTOP','WEB','UNKNOWN')),
  name         text not null default '',
  last_seen_at timestamptz not null default now(),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index devices_user_idx on public.devices(user_id);
create trigger devices_set_updated
  before update on public.devices
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- goals — mirrors the phone's GoalEntity (type + JSONB config/state)
-- ---------------------------------------------------------------------------
create table public.goals (
  id               uuid primary key default gen_random_uuid(),
  user_id          uuid not null default auth.uid() references auth.users(id) on delete cascade,
  type             text not null,   -- SHORTS_LIMIT / GITHUB_COMMIT / READING / FOCUS_SESSION / TASK / ...
  title            text not null default '',
  direction        text not null default 'ACHIEVE',   -- ACHIEVE / LIMIT
  schedule         text not null default 'ONE_SHOT',  -- ONE_SHOT / DAILY / ONGOING
  target           int  not null default 1,
  unit             text not null default '',
  progress         int  not null default 0,
  reward_points    int  not null default 0,
  penalty_shorts   int  not null default 0,
  config           jsonb not null default '{}'::jsonb,  -- repo/owner, recurrence, blocklist, …
  state            jsonb not null default '{}'::jsonb,  -- streak, periodEndAt, metThisPeriod, …
  active           boolean not null default true,
  deadline_at      timestamptz,
  deleted          boolean not null default false,
  origin_device_id uuid references public.devices(id) on delete set null,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);
create index goals_user_idx on public.goals(user_id);
create index goals_user_updated_idx on public.goals(user_id, updated_at);
create trigger goals_set_updated
  before update on public.goals
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- focus_sessions — cross-device focus history (mirrors FocusSessionEntity)
-- ---------------------------------------------------------------------------
create table public.focus_sessions (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id         uuid references public.devices(id) on delete set null,
  goal_id           uuid references public.goals(id) on delete set null,
  started_at        timestamptz not null,
  ended_at          timestamptz,
  planned_focus_min int  not null default 0,
  focused_min       int  not null default 0,  -- productive minutes (time-based phone / activity-based laptop)
  active_min        int  not null default 0,  -- input-active minutes (desktop; 0 on phone)
  cycles            int  not null default 1,
  violations        int  not null default 0,
  strict            boolean not null default false,
  outcome           text not null default 'ENDED_EARLY',  -- COMPLETED / ENDED_EARLY
  config            jsonb not null default '{}'::jsonb,    -- blocklist / allowlist snapshot
  deleted           boolean not null default false,
  origin_device_id  uuid references public.devices(id) on delete set null,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index focus_sessions_user_started_idx on public.focus_sessions(user_id, started_at desc);
create trigger focus_sessions_set_updated
  before update on public.focus_sessions
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- focus_schedules — recurring / scheduled sessions. Server owns the definition;
-- each device computes its own next run and self-triggers.
-- ---------------------------------------------------------------------------
create table public.focus_schedules (
  id                uuid primary key default gen_random_uuid(),
  user_id           uuid not null default auth.uid() references auth.users(id) on delete cascade,
  title             text not null default 'Focus',
  recurrence        jsonb not null default '{}'::jsonb,  -- {type:DAILY|WEEKLY|CUSTOM, days:[MON,…]}
  start_local_time  text not null,                        -- "HH:mm" in [timezone]
  timezone          text not null default 'UTC',
  focus_block_min   int  not null default 25,
  break_min         int  not null default 5,
  cycles            int  not null default 4,
  target_platforms  text[] not null default array['ANDROID'],  -- which devices auto-start
  config            jsonb not null default '{}'::jsonb,   -- blocklist / allowlist
  enabled           boolean not null default true,
  deleted           boolean not null default false,
  origin_device_id  uuid references public.devices(id) on delete set null,
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now()
);
create index focus_schedules_user_idx on public.focus_schedules(user_id);
create trigger focus_schedules_set_updated
  before update on public.focus_schedules
  for each row execute function public.set_updated_at();

-- ---------------------------------------------------------------------------
-- device_events — generic telemetry envelope (input-activity, app-focus, …)
-- ---------------------------------------------------------------------------
create table public.device_events (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id   uuid references public.devices(id) on delete set null,
  session_id  uuid references public.focus_sessions(id) on delete cascade,
  kind        text not null,   -- INPUT_ACTIVITY / APP_FOCUS / HEARTBEAT / …
  occurred_at timestamptz not null default now(),
  payload     jsonb not null default '{}'::jsonb,  -- {activeSeconds, idleSeconds, app, …}
  created_at  timestamptz not null default now()
);
create index device_events_session_idx on public.device_events(user_id, session_id);
create index device_events_time_idx on public.device_events(user_id, occurred_at desc);

-- ---------------------------------------------------------------------------
-- points_ledger — append-only economy (mirrors PointsEntryEntity). Balance = sum.
-- ---------------------------------------------------------------------------
create table public.points_ledger (
  id         uuid primary key default gen_random_uuid(),
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  delta      int  not null,
  reason     text not null default '',
  device_id  uuid references public.devices(id) on delete set null,
  created_at timestamptz not null default now()
);
create index points_ledger_user_idx on public.points_ledger(user_id);

create or replace function public.points_balance()
returns int
language sql
stable
security definer
set search_path = public
as $$
  select coalesce(sum(delta), 0)::int
  from public.points_ledger
  where user_id = auth.uid();
$$;

-- ---------------------------------------------------------------------------
-- Row-level security — a user can only ever touch their own rows.
-- ---------------------------------------------------------------------------
alter table public.devices         enable row level security;
alter table public.goals           enable row level security;
alter table public.focus_sessions  enable row level security;
alter table public.focus_schedules enable row level security;
alter table public.device_events   enable row level security;
alter table public.points_ledger   enable row level security;

create policy "own devices"         on public.devices         for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own goals"           on public.goals           for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own focus_sessions"  on public.focus_sessions  for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own focus_schedules" on public.focus_schedules for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own device_events"   on public.device_events   for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy "own points_ledger"   on public.points_ledger   for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Realtime — devices subscribe to these so schedules/sessions/goals fan out
-- live across a user's devices. RLS still filters each subscriber's rows.
-- ---------------------------------------------------------------------------
alter publication supabase_realtime add table public.goals;
alter publication supabase_realtime add table public.focus_sessions;
alter publication supabase_realtime add table public.focus_schedules;
