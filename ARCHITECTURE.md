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

`ShortsTrackerManager.effectiveLimit = base limit + extra earned today` — this is
what gates blocking, the dashboard ring, and the blocker.

## Adding a new tool (e.g. Focus sessions)

1. Create `features/focus/` with its data + ui.
2. Add a route in `presentation/navigation/AppNavigation.kt`.
3. Add an entry to `PRODUCTIVITY_TOOLS` in `ToolsHubScreen.kt`.
4. Earn/spend through `RewardsManager.award(points, reason)`.
5. Tool-specific permissions live on the tool's card (see the nudge tool).

## Reading verification

- **PDF path:** dwell verification — a page credits only after 20s on screen
  (`ReaderViewModel.PAGE_DWELL_MS`), each page credits once ever
  (`page_reads` PK). Flipping pages earns nothing.
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
