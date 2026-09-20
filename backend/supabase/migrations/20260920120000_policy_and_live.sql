-- focus_policy: per-user desktop enforcement rules, edited from the phone and read
-- by the laptop agent. App names are matched as case-insensitive substrings against
-- the active window's app; sites are domains to null-route in the hosts file.
create table public.focus_policy (
  user_id         uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  allowed_apps    text[] not null default '{}',   -- productive apps (input here counts)
  blocked_apps    text[] not null default '{}',   -- minimized when focused during a block
  force_quit_apps text[] not null default '{}',   -- terminated on sight during a block
  blocked_sites   text[] not null default '{}',   -- domains blocked via hosts file
  updated_at      timestamptz not null default now()
);
alter table public.focus_policy enable row level security;
create policy "own focus_policy" on public.focus_policy for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create trigger focus_policy_updated
  before update on public.focus_policy
  for each row execute function public.set_updated_at();

-- active_sessions: the single current live session per user. Starting a manual
-- session on one device upserts this row; other devices react via realtime and
-- start locally. Ending sets active = false.
create table public.active_sessions (
  user_id          uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  active           boolean not null default false,
  started_at       timestamptz,
  origin_device_id uuid references public.devices(id) on delete set null,
  config           jsonb not null default '{}'::jsonb,  -- focus/break/cycles/blocked/allowed
  updated_at       timestamptz not null default now()
);
alter table public.active_sessions enable row level security;
create policy "own active_sessions" on public.active_sessions for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create trigger active_sessions_updated
  before update on public.active_sessions
  for each row execute function public.set_updated_at();

alter publication supabase_realtime add table public.focus_policy;
alter publication supabase_realtime add table public.active_sessions;
