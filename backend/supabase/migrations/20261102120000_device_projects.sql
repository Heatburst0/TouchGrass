-- device_projects: projects the laptop agent found in the IDEs' own recent-project
-- lists (VS Code, JetBrains, Android Studio), so the phone can suggest real projects
-- when creating a work target — before any time has been tracked in them.
-- Only the project FOLDER NAME is stored, never its path.
create table public.device_projects (
  user_id    uuid        not null default auth.uid() references auth.users(id) on delete cascade,
  device_id  text        not null,
  app_name   text        not null,   -- lowercased window app name, as in device_apps (e.g. "visual studio code")
  project    text        not null,   -- folder name only
  last_seen  timestamptz not null default now(),
  primary key (user_id, device_id, app_name, project)
);
alter table public.device_projects enable row level security;
create policy "own device_projects" on public.device_projects for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
