# TouchGrass Architecture

Screen-time control + productivity toolset. The design goal: every new
productivity tool plugs in without touching the core.

## Layers

```
com.example.touchgrass
├── core/                     # shared infrastructure — tools consume, never duplicate
│   ├── analyzer/             # accessibility node-tree analysis (shorts detection)
│   ├── data/                 # SettingsRepository (DataStore) — small day-scoped state
│   │   └── db/               # Room: books, page_reads, points_ledger
│   ├── manager/              # ShortsTrackerManager — counting, limits, block events
│   ├── rewards/              # RewardsManager — the points economy (ledger-backed)
│   ├── screentime/           # ScreenTimeNudger — UsageStats-based nudges
│   └── service/              # InspectorService (accessibility)
├── features/                 # one folder per productivity tool, self-contained
│   └── reading/
│       ├── data/             # BookRepository (import, progress, page credits)
│       ├── pdf/              # PdfBookRenderer
│       └── ui/               # Library + Reader screens & ViewModels
└── presentation/
    ├── navigation/           # routes + bottom bar + NavHost
    ├── tools/                # ToolsHubScreen + PRODUCTIVITY_TOOLS registry
    ├── dashboard/            # Guard tab
    └── blockerView/          # full-screen block + earn-back CTAs
```

## The points economy

`RewardsManager` is the single door to earning/spending. Everything is a row in
`points_ledger` (balance = SUM(delta)), so history/gamification are free.

Current tariffs (constants in `RewardsManager`):
- verified page read: **+10 pts**
- +5 extra shorts today: **-20 pts** (day-scoped, stored in DataStore)

`ShortsTrackerManager.effectiveLimit = base limit + extra earned − penalty` — this
is what gates blocking, the dashboard ring, and the blocker. `penalty` is
`penaltyShortsToday` (day-scoped), docked when a commitment is missed.

## The goal spine (reward/punishment)

`GoalEngine` (`core/goals/`) is the pillar-agnostic reward/punishment loop:

- **`Commitment`** (Room `commitments`): a pledge — `pillar`, `targetAmount`
  `unitLabel`, `progress`, `deadlineAt`, `rewardPoints` (bonus on meet),
  `penaltyShorts` (docked on miss), `status` (ACTIVE/MET/MISSED).
- **`recordProgress(pillar, units)`** — any verified action calls this; the
  engine advances matching active pledges and pays the bonus when one is met.
- **`settleOverdue()`** — marks past-deadline pledges MISSED and applies the
  screen-time penalty. Called on app resume (`MainActivity`) and before each
  `recordProgress`.
- Reading is the first producer: verified pages call
  `recordProgress(PillarType.READING, n)`. Adding a pillar = call
  `recordProgress` when its verifier passes; no engine changes.
- UI: the **Goals** bottom-nav tab (`presentation/goals/`) creates pledges and
  shows active/history with won/missed status.

## GitHub daily-commit pillar (recurring, background-verified)

`features/github/` — a second, recurring producer feeding the same economy:

- **`GitHubGoalEntity`** (Room `github_goals`, day-string state): owner/repo,
  optional author, streaks, `lastSuccessDate`/`lastSettledDate`.
- **`GitHubApi`** (OkHttp): `hasCommit(repo, since, until)` against the public
  commits API. Public repos need no auth; private repos use an optional PAT
  (`SettingsRepository.githubToken`). 404/403/401 → typed error; **network
  errors propagate so a miss is never recorded on a failed check**.
- **`GitHubGoalManager.runChecks()`**: credits a same-day commit (reward +
  streak via `RewardsManager`) and settles each elapsed day — a day with no
  commit docks `penaltyShorts` and resets the streak. Fair-by-construction:
  penalties require a definitive API "no".
- **`GitHubCheckWorker`** (`@HiltWorker`, WorkManager periodic ~3h + on Goals
  open) runs it in the background so misses settle even when the app is never
  opened. `TouchGrassApp` implements `Configuration.Provider` and enqueues the
  unique periodic work.

This is the cleanest verifier: a commit is un-fakeable proof-of-work already
sitting on a public API — no quiz/photo needed.

## Adding a new pillar (e.g. Focus sessions)

1. Add the `PillarType` enum entry (`core/goals/Goal.kt`) — already stubbed for
   FOCUS/GYM/LEARNING.
2. Build its verifier (its own way of proving the task was done).
3. On a pass, call `goalEngine.recordProgress(PillarType.X, units)` — that alone
   wires it into commitments, rewards, and punishment.
4. Create `features/<pillar>/` with data + ui; add a route + `PRODUCTIVITY_TOOLS`
   entry (`ToolsHubScreen.kt`) if it needs a launcher.
5. Tool-specific permissions live on the tool's card (see the nudge tool).

## Reading verification

Points are quiz-gated for BOTH book types — dwell/photos alone never pay out.

- **PDF path:** 20s dwell marks a page PENDING (`page_reads.verified = 0`,
  no points). The reader's bottom bar offers a quiz (up to 4 pending pages
  per round) built from server-rendered page bitmaps; pass ≥2/3 flips the
  pages to verified and awards the points. Bottom bar also has a page
  scrubber slider for jumping to any page.
- **Physical book path:** photograph pages → multimodal LLM generates 3 MCQs
  answerable only from the page text → pass (≥2/3) credits each photographed
  page. Provider-agnostic `QuizGenerator` interface
  (`features/reading/quiz/`); current impl is `GeminiQuizGenerator` on the
  Gemini API **free tier** (chosen because Claude API has no free tier).
  Swap providers by changing the `@Binds` in `di/QuizModule.kt`.
  Setup: put `GEMINI_API_KEY=...` in `local.properties`
  (free key from aistudio.google.com) and rebuild.

## Accessibility service resilience

- Battery-optimization exemption requested from the dashboard.
- With `WRITE_SECURE_SETTINGS` (adb: `adb shell pm grant com.example.touchgrass
  android.permission.WRITE_SECURE_SETTINGS`) the app re-enables its own service
  on every app resume (`tryForceEnableAccessibility`).
- The service watches YouTube/Instagram (shorts detection) + Netflix
  (watch-time only, for nudges).
