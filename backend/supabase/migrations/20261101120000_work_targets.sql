-- work_targets: an app (optionally one project inside it) the user wants to spend
-- time in. Drives two features:
--   * focus quotas — weekly minutes live in weekly_plans.plan.focusQuotaMinutes,
--     keyed by work_targets.id (so they're lockable with the rest of the plan);
--   * auto-focus — the laptop agent starts a focus session once the user has been
--     actively working on the target for auto_focus_after_min minutes.
create table public.work_targets (
  id                    uuid        primary key default gen_random_uuid(),
  user_id               uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  label                 text        not null,
  app_name              text        not null,                -- lowercased substring of the active app (device_apps.app_name)
  project               text,                                 -- optional, case-insensitive match on the project parsed from the window title
  auto_focus            boolean     not null default false,
  auto_focus_after_min  int         not null default 3  check (auto_focus_after_min between 1 and 60),
  focus_min             int         not null default 50 check (focus_min between 5 and 240),
  block_sites           boolean     not null default true,
  block_phone           boolean     not null default true,  -- also block distracting apps on the phone
  enabled               boolean     not null default true,
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now()
);
alter table public.work_targets enable row level security;
create policy "own work_targets" on public.work_targets for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create trigger work_targets_updated
  before update on public.work_targets
  for each row execute function public.set_updated_at();
alter publication supabase_realtime add table public.work_targets;

-- app_usage: active (input-confirmed) seconds per device, local day, app and
-- project, counted all the time — not only inside focus sessions. Only the parsed
-- project name is stored, never window titles or file names.
create table public.app_usage (
  user_id         uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  device_id       text        not null,
  day             date        not null,               -- the device's local calendar day
  app_name        text        not null,               -- lowercased, as in device_apps
  project         text        not null default '',    -- '' when no project could be parsed
  active_seconds  int         not null default 0,
  updated_at      timestamptz not null default now(),
  primary key (user_id, device_id, day, app_name, project)
);
alter table public.app_usage enable row level security;
create policy "own app_usage" on public.app_usage for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create index app_usage_user_day on public.app_usage (user_id, day);

-- Atomic increment so concurrent flushes never lose seconds. Runs as the caller,
-- so RLS still applies; per-call and per-day caps bound a misbehaving client.
create or replace function public.add_app_usage(
  p_device_id text, p_day date, p_app text, p_project text, p_seconds int
) returns void
language sql
security invoker
as $$
  insert into public.app_usage (user_id, device_id, day, app_name, project, active_seconds)
  values (auth.uid(), p_device_id, p_day, lower(p_app), coalesce(p_project, ''),
          greatest(0, least(p_seconds, 3600)))
  on conflict (user_id, device_id, day, app_name, project)
  do update set active_seconds = least(app_usage.active_seconds + excluded.active_seconds, 86400),
                updated_at = now();
$$;
grant execute on function public.add_app_usage(text, date, text, text, int) to authenticated;
