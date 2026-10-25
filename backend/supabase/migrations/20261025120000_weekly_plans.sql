-- weekly_plans: the user's weekly commitments (force read, Shorts limit, blocked apps,
-- reading pages, accessibility penalty, focus quotas) — editable Sat/Sun, locked
-- Mon 00:00 → Sat 00:00, where the app only allows making them stricter. Stored
-- server-side so clearing app data or reinstalling restores the lock at sign-in.
create table public.weekly_plans (
  user_id          uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  plan             jsonb not null default '{}'::jsonb,   -- WeeklyPlan fields
  committed        boolean not null default false,       -- locked Mon–Fri once true
  unlock_requests  jsonb not null default '[]'::jsonb,   -- [{field, requestedAtMs}] paid loosen requests
  timezone         text,                                 -- IANA zone the Mon–Fri window is judged in
  updated_at       timestamptz not null default now()
);
alter table public.weekly_plans enable row level security;
create policy "own weekly_plans" on public.weekly_plans for all
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create trigger weekly_plans_updated
  before update on public.weekly_plans
  for each row execute function public.set_updated_at();

-- Once committed, a plan stays committed and can't be deleted (the app never
-- un-commits), so a hand-made API call can't simply drop the lock.
create or replace function public.weekly_plans_guard()
returns trigger
language plpgsql
as $$
begin
  if tg_op = 'DELETE' then
    if old.committed then
      raise exception 'a committed weekly plan cannot be deleted';
    end if;
    return old;
  end if;
  if old.committed and not new.committed then
    raise exception 'a committed weekly plan cannot be un-committed';
  end if;
  return new;
end;
$$;

create trigger weekly_plans_guard
  before update or delete on public.weekly_plans
  for each row execute function public.weekly_plans_guard();

alter publication supabase_realtime add table public.weekly_plans;

-- Trusted clock for the weekly lock: the phone's own clock can be changed to fake
-- the weekend, the server's can't.
create or replace function public.server_now()
returns timestamptz
language sql
stable
as $$ select now() $$;

grant execute on function public.server_now() to authenticated;
