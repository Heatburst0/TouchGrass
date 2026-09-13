# TouchGrass backend (Supabase)

Postgres + Auth + Realtime that the phone and the laptop agent share. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the design.

## One-time setup

1. **Create a project** at [supabase.com](https://supabase.com) (free tier is fine).
   Note the **Project URL** and **anon public key** (Project Settings → API).

2. **Install the CLI** (`npm i -g supabase` or `scoop install supabase`), then from
   this `backend/` folder:
   ```bash
   cd backend
   supabase login
   supabase link --project-ref <your-project-ref>
   ```

3. **Apply the schema** (the migration in `supabase/migrations/`):
   ```bash
   supabase db push
   ```
   This creates the tables, RLS policies, the `points_balance()` function, and adds
   `goals` / `focus_sessions` / `focus_schedules` to the realtime publication.

4. **Enable auth providers** in the dashboard (Authentication → Providers):
   - **Email** (magic link) — on by default.
   - Optionally **Google** for one-tap sign-in.
   Add your redirect URL `touchgrass://auth-callback` under Authentication → URL
   Configuration → Redirect URLs.

## Local development (optional)

```bash
cd backend
supabase start      # spins up Postgres + Auth + Realtime + Studio in Docker
supabase db reset   # applies migrations to the local db
```
Studio: http://localhost:54323

## Adding a change

Never edit applied migrations. Create a new one:
```bash
cd backend
supabase migration new <name>
# edit supabase/migrations/<timestamp>_<name>.sql
supabase db push
```

## Where the keys go (never commit them)

- **Phone:** the Project URL + anon key go into the app's `local.properties`
  (git-ignored) and are exposed via `BuildConfig` — added in Phase 2.
- **Laptop agent:** the same two values go into the agent's config / `.env`
  (git-ignored) — added in Phase 3.

The **anon key is public by design**; row-level security is what protects data, so
never ship the **service_role** key to a client.
