-- device_apps: apps the laptop agent has seen in the foreground, reported so the
-- phone can offer a "pick from apps you actually use" list in the rules editor.
-- app_name is the lowercased match key (compared against the active window); the
-- user's choices are written into focus_policy, never back into this table.
create table public.device_apps (
  user_id      uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  device_id    text        not null,
  app_name     text        not null,   -- lowercased match key
  display_name text        not null,   -- shown in the phone picker
  last_seen    timestamptz not null default now(),
  primary key (user_id, device_id, app_name)
);
alter table public.device_apps enable row level security;
create policy "own device_apps" on public.device_apps for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());

alter publication supabase_realtime add table public.device_apps;
