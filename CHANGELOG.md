# Changelog

All notable changes to AlphaPulse are recorded here. The GitHub Release for each `v*` tag
publishes the newest section of this file, so the release page and the repository never
disagree about what shipped.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the app's
own `versionName` (`1.0.<versionCode>+<sha>`), which is stamped by CI.

## [19.9] — 2026-11-25

One workspace, one coin, every mode reachable, and the Audit tab reads like a ledger.

### Fixed

- **The Dashboard now inspects the coin AlphaPulse selected (C-62).** The two screens had two
  selections: AlphaPulse booted from the cache's first row while the campaign engine chose the
  first row of its own universe, and a tab tap launched a second activity with a second pair of
  view models, re-running the screening boot under the operator. The selection is now one
  process-wide `WorkspaceSelectionStore`: AlphaPulse publishes it, the engine adopts it, a
  universe refresh keeps it (the first-row fallbacks are gone), and `WorkspaceSelection.resolveSymbol`
  makes "the selected coin, never a substitute" a single tested rule.
- **Switching tabs no longer interrupts the desk (C-62).** `MainActivity` mounts the workspace in
  the same composition as the analysis screen once the module is installed, so a tab switch is
  local state instead of `FLAG_ACTIVITY_NEW_TASK`. The tab and coin are saveable across rotation,
  each tab body is retained through `rememberSaveableStateHolder`, and a refresh keeps the
  inspected board by id instead of resetting to the book's first board.
- **Every execution mode switches, or says why it cannot (C-63).** Shadow and Paper previously
  changed the pill and nothing else, and the live-mode refusal was recorded but never rendered.
  `ExecutionModeGate` now decides, the workspace shows the refusal in a dialog, a mode banner
  states what the active mode does, and the accepted mode is persisted across launches.
- **Paper mode is no longer suppressed by venue latency (C-63).** `VenueHealth.isHealthy` folded a
  stale feed, a Binance latency spike, an unreachable optional CoinGlass feed and device power
  state into one refusal, so every Paper board was logged as `STAGING_GATE_PAPER / Venue or
  hardware health guardrail tripped`. `VenueHealthVerdict` keeps a stale feed hard for every mode,
  keeps latency/power/thermal/memory hard for the live modes, and reports them as advisories in
  Shadow and Paper — which route nothing.
- **The Audit tab is a pinned ledger, not 200 cards (C-64).** One header with `shown/total` counts,
  one filter row (`ALL` / `ORDERS` / `RISK` / `OPERATOR` / `SYSTEM`) with per-scope counts, and one
  `LazyColumn` table whose `TIME`/`ACTION` columns stay pinned while `SYMBOL`/`REASON`/`MESSAGE`
  share a scroll. Tapping a row opens its full message.

### Added

- `ui/WorkspaceSelection.kt`, `ui/ExecutionModeGate.kt`, `data/local/ExecutionModeStore.kt`,
  `engine/VenueHealthVerdict.kt`, `ui/AuditLedgerPresentation.kt`.
- Guard specs `WorkspaceSelectionBddSpec`, `SingleWorkspaceHostBddSpec`, `TabStateRetentionBddSpec`,
  `LiveModeGateBddSpec`, `VenueHealthStagingBddSpec`, `AuditLedgerPresentationBddSpec` and
  `AuditLedgerLayoutBddSpec`; the registry is pinned through C-64.

## [19.8] — 2026-11-24

Evidence expires now: a rate from outside its in-force window is refused everywhere, and the window is a setting.

### Added

- **In-force expiry (C-61).** Every qualification decision now compares how long ago a layer was measured
  against one configured window. `ThresholdConfig.evidenceTtlMinutes` (60 as shipped, valid 5–1440) defines it,
  and `inForceWindowMinutes(scope)` gives a horizon the longer of that window and twice its own span, so a 1h
  cone is not declared stale the moment its newest settlement lands. The rule is one pure helper,
  `EvidenceInForce.status`: inclusive at the boundary, and a record with no measurement timestamp **fails
  closed** instead of being trusted forever.
- **`IN-FORCE MIN` in Guardrails → `EVIDENCE THRESHOLDS`.** The window steps in 5–1440 minutes beside the floor
  and sample floors, every readout prints why a rate is stale
  (`PRICE_EVIDENCE_STALE(age 61min, in-force window 60min)`), and an out-of-range candidate is refused by name.
- **An executing spec for the whole threshold path.** `ThresholdChangePathBddSpec` constructs the shipped
  `MainViewModel`, drives `adjustThresholds`/`resetThresholds`, and reads back the store, the `decision_records`
  row and the rebuilt evidence's in-force window — closing the gap the previous feature left at the view-model seam.

### Changed

- **Stale evidence is stale everywhere.** `SignalEvidence` horizons, `LayeredEvidence`'s PRICE/PATTERN/STRATEGY
  rows and `CampaignReadinessGate`'s governing cones all read the same helper: a stale price horizon can no longer
  stage a board, a stale additive layer is named stale and stays inactive, and a stale cone refuses arming with
  `HORIZON_15m_CONE_EVIDENCE_STALE(age 61min, in-force window 60min)`.
- **Evidence carries when it was measured.** `ScreenedUniverseBridge` stamps each horizon from its newest settled
  prediction, and the view model restamps the rebuilt evidence with the live window after an accepted change, so no
  cached layer keeps judging a coin by a limit that is no longer in force.
- **A stored window survives the restart**, and a payload written before the setting existed keeps the operator's
  configured floor at the shipped window rather than being discarded.

## [19.7] — 2026-11-24

The 85% floor is a setting now, not a constant — and the tab that moves it records every change.

### Added

- **Guardrails → `EVIDENCE THRESHOLDS` (C-60).** The Gate tab now states the limits the gate is
  applying: per layer, the configured floor and sample floor (20 settled for the 1m/5m tier, 4 for
  15m/30m/1h, 20 graded calls for STRATEGY) and, beneath each, the inspected coin's own rates
  against them. The floor steps in a range clamped to 50–95% inclusive, the sample floors step
  individually, and `RESET TO 85.0% / 20` restores exactly the shipped values.
- **One persisted configuration.** `ThresholdConfig` is stored through
  `SharedPreferencesThresholdStore` (validated on load, so a corrupt or out-of-range payload falls
  back to the shipped defaults), installed into `Thresholds` at launch and after every accepted
  change. `SignalEvidence.MIN_HIT_RATE_PCT`, `LayeredEvidenceGate`'s price/pattern/strategy record
  and `CampaignReadinessGate`'s default floor now all read it at evaluation time, so one change
  moves every gate and no consumer keeps a live hardcoded copy.
- **Every accepted change is a ledger decision.** `ThresholdConfigService` persists, installs and
  writes one `CONFIG_UPDATE` row to `decision_records` — the inspected coin, scope `GLOBAL`, the
  field, the old value, the new value and the timestamp — so the trade-decisions ledger explains
  why a gate moved. A refused candidate writes nothing at all.

### Changed

- **Refusals name the reason.** A candidate outside 50–95%, or with a zero sample floor, is
  rejected rather than silently clamped: a zero PRICE floor would leave PRICE unproven but
  unblocking, so the card prints `PRICE_UNPROVEN_BUT_UNBLOCKING(…)` and keeps the live
  configuration exactly as it was.
- **The Gate card's header prints the configured floor**, not a literal `85%`, and the evidence
  rows are rebuilt from the persisted ledgers after an accepted change so no cached layer keeps
  judging a coin by the previous sample floor.

## [19.6] — 2026-11-24

One verdict per row now says so: the trade-decisions ledger marks the call that is still in force.

### Added

- **`IN FORCE` and `since HH:mm:ss` on the live verdict (C-29).** Coalescing made the ledger
  hold one row per verdict instead of one per ten-second cycle, but a lone `NEUTRAL` still
  looked exactly like a stale row. The newest pending verdict per coin and reference horizon
  now reads `IN FORCE`, and its time cell reads `since HH:mm:ss` — the moment its state
  began — while every older row keeps `PENDING` and its plain clock. The projection is pure
  (`VerdictInForce`): once the newest verdict for a horizon is graded, nothing on that
  horizon claims to be in force, and a coin never inherits another coin's marker.

### Changed

- **The decisions ledger's `TIME` column is wide enough for the marker** (70dp → 96dp, header
  and cells together) so `since 14:03:22` cannot clip at 360dp.

## [19.5] — 2026-11-23

The gate is readable, the selected coin stays selected, and the chrome stops eating the screen.

### Added

- **The layered gate is visible where the limits are read (C-58).** The Guardrails tab now
  renders PRICE, PATTERN and STRATEGY for the coin under inspection: `ACTIVE` with the
  horizons behind it once proven, `BLOCKING` for a price layer that withholds boards, and
  `INACTIVE` with the sample still missing for an additive layer. The projection is pure
  (`LayeredEvidencePresentation`) and pinned by its own scenario.
- **One row per verdict, not one per cycle (C-29).** A cycle that reproduces the newest
  verdict for the same coin, reference horizon and answering router no longer writes a
  second `decision_records` row (`DecisionCoalescing`), so a quiet coin's repeated
  `NEUTRAL` stops filling the trade-decisions ledger every few seconds; a changed state,
  horizon, router or coin is still recorded and nothing already written is rewritten.

### Changed

- **`CampaignReadinessGate` reads the confirmed 85% floor.** The unused helper carried its
  own strict `> 90%` default, so the same coin could be judged ready by one gate and
  unready by the other; it now uses `SignalEvidence.MIN_HIT_RATE_PCT` inclusively (85.0%
  passes, 84.99% does not) and its guard spec exercises the shipped class.
- **The inspected coin survives a universe refresh (C-22/C-58).** A refresh may update the
  selected coin's data but never replace it, and a coin withheld from the campaign universe
  because its price layer is unproven stays inspectable instead of being silently swapped
  for the list's first row.
- **Compact chrome.** The shared header is trimmed (smaller identity block, 6dp vertical
  padding, tighter rows) and the shared bottom bar draws its own 48dp row over the
  navigation-bar inset instead of the Material bar's 80dp plus item padding.

### Fixed

- **Oversized prices.** The Dashboard price and the Level Map `Last:` label printed the raw
  `Double` (`$72.92500000000001`, wrapping onto a second line); both now use the shared
  five-decimal formatter.
- **The emulated billing dialog no longer quotes a `$49.99` price it does not charge.**

## [19.4] — 2026-11-23

One header, one navigation bar, and a ProactiveMS gate that layers its evidence.

### Added

- **Layered evidence (C-58).** ProactiveMS no longer asks whether one blended number is
  good enough. Its evidence is layered: **PRICE** is the only blocking layer, **PATTERN**
  becomes an additional input per coin and horizon once that horizon's settled formations
  clear 85%, and **STRATEGY** becomes an additional input once the coin's graded §74 rows
  clear 85% over at least twenty graded calls. An unproven additive layer is inactive and
  never withholds a board the price layer has proven; every verdict names the layers that
  were live. The only production staging path (`MainViewModel.gateAndLog` →
  `ExecutionEngine.gateStaging`) passes the verdict, so a board whose price horizons have
  not proven themselves is held `PLANNED` with no resting slices and a gate note naming
  the horizon and its sample.
- **Strategy grading (C-59).** Room schema **version 13** adds `settled`, `outcome` and
  `resolvedAt` to `strategy_signal_ledger` through the production 12 → 13 additive
  migration. Each §74 row is graded exactly once against the bar that follows it: a long
  (short) is a HIT when its entry trades before its invalidation, and a MISS when the
  invalidation trades first, when the bar touched both, or when neither level traded. A
  NEUTRAL or level-less signal is UNGRADED and excluded from the rate.

### Changed

- **The confirmed 85% evidence floor (C-55).** The feeding gate is now 85% with
  integer comparison, so exactly 85.0% qualifies, over a sample floor that scales with
  how often a horizon settles: 20 for `1m`/`5m` and 4 for `15m`/`30m`/`1h`. It is still
  evaluated per coin and per horizon, never as one blended rate.
- **Unproven coins leave the campaign universe.** A coin with no proven price horizon is
  no longer carried as a neutral asset with a "forecast withheld" note; it is removed
  from the bridged list, so a board can never be shaped for a coin with no evidence.
- **One header (C-57).** `AlphaPulseHeader` is the app's only header. It always shows the
  AlphaPulse brand, screening status, session clock, readiness badges and refresh, and
  gains the VIP block — live/offline chip, execution-mode pill, kill switch, orphan
  guard, worst-case stop-out, ping, RAM and session lock — only in the host that mounts
  the campaign engine. The base app passes no VIP block, so unlocking still cannot alter
  the analysis screen. `TopBar` and `HeaderBar` are deleted, and the Chronos-Bolt /
  TimesFM-2.5 quick actions moved onto the `ML ENSEMBLE WEIGHTS` card they feed.
- **One navigation bar (C-57).** `AlphaPulseNavBar` renders exactly one `VIP Features`
  slot before entitlement and the six canonical tabs (first labelled `Alpha Pulse`) once
  the module is installed. The ProactiveMS workspace mounts the same `AlphaPulseTabBar`,
  a cross-host launch carries its tab through `EXTRA_INITIAL_TAB`, and
  `TraderHostActivity` refuses to mount without the persisted entitlement.
- **Pattern hit rate over settled rows only.** The pattern ledger's rate divided by every
  issued row, mixing ungraded predictions into the denominator; it now measures settled
  rows and uses the confirmed 85% colour boundary.

## [19.3] — 2026-11-23

§74's strategy signals survive the cycle that produced them.

### Added

- **The strategy signal ledger (C-56).** The nine quantitative models and seven technical
  screeners were executable, but their output lived only inside the evaluation that
  computed it: the board painted the last call and the previous call's entry, stop and
  invalidation were gone. Room schema **version 12** now reaches
  `strategy_signal_ledger` through the production 11 → 12 migration, and every evaluation
  records one row per strategy for the bar the rules read — rule id and name, direction,
  entry/exit/invalidation, ATR distance, zone touches, the rule's own detail text, the bar
  time and the issue time. NEUTRAL readings are recorded too, because "this rule saw
  nothing on this bar" is evidence.
- The row is keyed `asset/strategyId/barOpenTime` and written with `REPLACE`, so a
  ten-second cycle re-reading the same bar replaces its rows instead of appending
  duplicates, and the table is bounded to a seven-day window per asset. A ledger write
  failure is logged rather than allowed to abort an evaluation cycle, and the price and
  pattern ledgers' schemas are untouched (C-23).

## [19.2] — 2026-11-23

A forecast reaches a campaign only once that coin's own settlements have proven it.

### Added

- **The §22 feeding gate (C-55).** `SignalEvidence` requires at least 20 settled
  predictions at an 80% cone hit rate — per coin and per horizon, because a coin's 5m
  cone can be excellent while its 1h cone is worse than a coin flip. The gate fails
  closed: `ScreenedUniverseBridge.forecastTargetsFor` admits only proven horizons, an
  unproven forecast shapes neither the asset's bias nor its regime, and the board's
  pain path now reads `Forecast withheld until evidence: 1h 71% of 34 settled (needs
  80% of 20)` instead of blaming a missing forecast. §22's staging gate (C-19) still
  requires its own > 90% before a board may stage.

## [19.1] — 2026-11-23

The legacy Screener tab is retired, and every remaining tab works from AlphaPulse.

### Removed

- **The `Screener` tab, the shared symbol strip and `+ BTC ONLY`.** The tab listed the
  same coins AlphaPulse had already screened, under the `$50M–$750M` mid-cap band C-17
  removed, and it made a second coin selector that could disagree with the campaign
  engine. The workspace now has six tabs — `AlphaPulse`, `Dashboard`, `Telemetry`,
  `Guardrails`, `Replay`, `Audit` — and opens on `AlphaPulse`, and
  `UnifiedUniverseScreener.screenUniverse` no longer takes a BTC-only override, because
  nothing may override the gate's verdict (C-22).
- `ScreenerScreen.kt`, `SymbolStrip`, `SymbolChip`, `availableSymbols`, `btcOnly` and
  `BTC_ONLY_SYMBOL` are deleted rather than left unused.

### Changed

- **AlphaPulse is the one coin selector.** A coin chosen in its institutional screen is
  mirrored into the workspace and adopted by the campaign engine through the new
  `MainViewModel.adoptScreenedUniverse`, which reloads the screened universe from the
  same store the campaigns read and never invents a symbol the screen did not admit.
- The Dashboard's empty state now says `Select a coin in AlphaPulse`, and §72 / C-22, the
  traceability matrix and the audits record six tabs instead of seven.

## [19.0] — 2026-11-23

The screened universe stops depending on the hour it was taken, and a small universe can
now say which leg rejected the pairs.

### Fixed

- **The velocity leg was judged on a half-formed day.** `/fapi/v1/klines?interval=1d`
  returns the UTC day still forming, so its `quoteVolume` at 06:00 is about a quarter of
  a day's turnover. Divided by full live open interest, a coin that really turns over
  1.6x a day measured 0.4x and failed the §65 velocity leg — the same coin was admitted
  at midnight and rejected at dawn, which is exactly the "drastic reduction in screened
  coins" reported after v1.0.15. Today's snapshot is now built from the ticker's
  **rolling 24-hour** quote volume (`todayFromKline`), which is comparable with live open
  interest whenever the screen runs; the taker-buy ratio and the displacement stay the
  candle's own, because those are ratios of that candle (C-37).
- **A shrinking universe said only that it shrank.** The screen now counts survivors per
  leg — `pairs → volume → taker → velocity → displacement → admitted`, plus the pairs
  whose open interest neither Binance nor CoinGlass could price — and reports the funnel
  on the pipeline subtitle and on the notice shown when a cached universe is retained
  (`UniverseFunnel`). A failed open-interest read is still never substituted.

### Changed

- **Persistence is six of the last ten sessions, not eight.** With the honest, strict
  gate in place, two quiet sessions were evicting a coin that is otherwise persistent,
  and the universe collapsed to a handful of pairs. Six still requires a majority of the
  window, so one lucky day is still not a universe (C-37).

## [18.7] — 2026-11-22

The ProactiveMS workspace draws one header and one set of window insets for all seven
tabs, and its AlphaPulse tab renders the real AlphaPulse analysis instead of repeating
the workspace chrome.

### Fixed

- **One header, one set of insets.** The AlphaPulse tab used to nest the whole
  AlphaPulse screen, so it brought its own `Scaffold`, `safeDrawing` padding and header
  while the workspace drew another for every other tab; the result was stacked chrome
  and content pushed off the screen. `AlphaPulseScreen` now splits into the standalone
  entry (unchanged `Scaffold`, header and insets, still used by `MainActivity`) and a
  body-only `AlphaPulseScreenContent`, which is what the workspace mounts. The workspace
  draws `TopBar` once for every tab, including AlphaPulse.

### Changed

- **The AlphaPulse tab is the AlphaPulse screen.** The tab now shows the session timer,
  decision card and analysis flow of the base screen rather than the four-line summary
  that duplicated the AlphaPulse destination (C-22). Its own coin selector replaces the
  workspace symbol strip on that tab, so the selected symbol is still shared and never
  shown twice.

## [18.6] — 2026-11-22

The ProactiveMS workspace finally renders its seven tabs where the contract says they
are, and a sideloaded release now carries the on-demand trader module.

### Fixed

- **The seven-tab navigation is a bottom bar.** `TraderHostActivity` rendered the
  canonical tab order as a horizontally scrolling strip at the top of the screen, so the
  shipped workspace had no bottom navigation at all and the tabs scrolled away with the
  header. The tabs are now the host's bottom bar, one equal-weight slot per tab, pinned
  below the body so all seven stay reachable on a phone width (C-22).

### Changed

- **One tab order, one surface.** `AppDestination` and `AppBottomNavigationBar` in the
  base module had zero call sites, duplicated `ProactiveMsWorkspaceState.TAB_ORDER` and
  disagreed with it — `AlphaPulse` was last instead of second. Both are deleted, so the
  canonical enum in `ProactiveMsNavigation.kt` is the only tab order in the app.

### Added

- **The release carries the fused universal APK.** `app-arm64-v8a-universal-debug.apk`
  is built by bundletool from the verified `.aab` and verified to contain
  `com.alphapulse.proactive.TraderHostActivity`; it is the only sideloadable asset that
  can start the VIP workspace, so installing the debug or release variant still shows
  `VIP unavailable`. Install the universal APK to use the trader module outside Play
  (C-03/C-04).

## [18.5] — 2026-11-22

The Robolectric cache can no longer be lost, quietly renamed, or left short of the
SDKs the suite actually runs on.

### Added

- **The runtime cache is a checked contract.** `scripts/robolectric_runtime.json`
  records the pinned Robolectric version, the cache key and the `android-all`
  artifact for every SDK it covers. `scripts/check_ci_preflight.py` and
  `build/RobolectricRuntimeCacheBddSpec.kt` both read that one file, so the
  workflow key, the cold-cache prefetch and the SDKs the suite pins with
  `@Config` (plus the `targetSdk` a spec inherits) must agree. Losing the cache
  step, renaming its key, dropping a prefetched artifact or adding an SDK without
  its jar now fails preflight in seconds — before the test matrix downloads
  anything.

### Fixed

- **The cold-cache prefetch actually fetches.** It wrote and requested
  `<version>.jar` inside the artifact's directory, but Maven names those files
  `android-all-instrumented-<version>.jar`, so every URL 404ed, the step only
  warned, and a cold runner fell back to Robolectric's own download. The filename
  is now built from the artifactId, and both the preflight gate and the spec
  assert it so the backstop cannot rot silently.

### Changed

- **The cache key is derived from what it caches.** A key must name the pinned
  version and every covered SDK (`robolectric-android-all-4.16.1-sdk33-36-v1`),
  so bumping either without a new key fails instead of restoring stale jars and
  downloading the missing one mid-test (C-28).

## [18.4] — 2026-11-22

A held board now says why on the card, and a CI test run no longer depends on a
fresh Robolectric download.

### Changed

- **The §22 staging hold is readable on the board.** `ExecutionEngine` puts the
  refusal on the campaign itself (`gateNote`) and `CampaignCard` renders it directly
  under the header as an amber `STAGING GATE` strip, naming each missing input the way
  §22 names it — `TSFM Ready not observed`, `Liquidity Heatmap Ready unavailable` — so
  a held board no longer looks like an unexplained plan. The machine-readable
  `TSFM_UNOBSERVED` tokens stay in the audit note, not on the card (C-54).
- **A stale reason cannot outlive its verdict.** The gate clears `gateNote` on every
  pass that does not hold the board, so a routed board never carries last cycle's
  refusal.

### Fixed

- **CI no longer fails on a Robolectric download.** Robolectric fetches its
  `android-all-instrumented` jars from Maven Central while the tests run, outside
  Gradle's cache; a 429 from that host killed the API 34 leg after every Gradle
  dependency had resolved. The unit-test job now caches those jars under a key tied to
  the Robolectric version and pre-populates them with a bounded, retrying download on
  a cold cache, so later runs restore instead of downloading.

## [18.3] — 2026-11-22

§22 staging precondition: a campaign may no longer stage on a pipeline that is only
partly warm.

### Changed

- **All four §22 readiness components must be observed before a board stages.**
  `TSFM Ready`, `Neural LOB Ready`, `Liquidity Heatmap Ready` and `Ensemble Ready` are
  now an AND enforced inside `ExecutionEngine.gateStaging`, which takes the verdict as a
  required argument and holds the board `PLANNED` with its resting slices cleared when any
  component is missing (C-54).
- **Absence fails closed.** A component with no evidence is `UNOBSERVED`, which blocks; an
  explicitly failed measurement is `UNAVAILABLE`; the gate note names every unmet
  component, so a held board says which feed is cold instead of looking like a risk stop.
- **The verdict comes from what the app observed.** `MainViewModel` derives it per symbol
  from the persisted ladder's model tier, the live two-sided book, a heatmap row inside
  the map's 24-hour window and the ladder's horizon count, and drops the data-driven
  components back to `UNOBSERVED` when a probe cannot read them. A cold
  `ProactiveExecutionLoopFrame` defaults to no evidence and therefore cannot stage.

## [18.2] — 2026-11-22

Phase 2 risk and structure hardening: a symbol can no longer be double-booked, the
published priority is structure rather than role, spent levels stop scoring, and the
candle cache stops serving a four-hour-old 5m series.

### Changed

- **A second same-side board on one symbol is refused, and the cap is two live boards.**
  `RiskEngine` admits a board only when it outranks the peers already live on that asset
  (higher priority, or the earlier id on a tie), so a re-planned book keeps exactly one
  long and one short per symbol deterministically and the rest are suppressed with a
  reason naming the asset, the cap and the owning board (C-50).
- **Worst-case stop-out nets the book.** `computeStopOutRisk` offsets long and short
  budgets per symbol and applies that symbol's widest real ladder-to-hard-stop distance
  instead of adding every notional at a fixed 2.5%, so a hedge no longer reads as two
  independent losses and a wide stop no longer reads as a tight one (C-50).
- **`PRIORITY RANK` is the published product, not a role base.** A campaign's priority is
  path probability × path cleanliness × level confidence, bounded 0-100 (C-51).
- **A level is spent at its third touch.** `LevelZone.isExhausted` and
  `StructureEngine.zoneConfidence` cap touch points at `TOUCH_CEILING`, so a fourth or
  ninth tap adds no confidence and the scorer agrees with the ledger's
  `Exhausted (Touch 3/3)` (C-52).

### Fixed

- **The 5m candle series could be nearly four hours old.** The Room-first cache applied
  the four-hour HTF staleness window to every interval, so structure detection and
  calibration could map the morning's swings while price traded elsewhere. Each series is
  now reused for its own horizon — 4h for four hours, 1h for one hour, 5m for five minutes
  — and data exactly at the window is stale (C-53).

### Verification

- BDD specs: `ConcurrencyAndStopOutBddSpec` (cap, single owner, deterministic admission,
  netted and hedged stop-out), `CampaignPriorityAndZoneExhaustionBddSpec` (the product,
  bounded maximum, touch ceiling and exhaustion) and `CandleCacheTtlBddSpec` (per-interval
  windows and the boundary). All were run red against the missing behaviour first.
- Full local gates: `:app:testDebugUnitTest` (915 tests, 4 skipped), `check_integrity.py`
  (356 files), `:app:assembleDebug` and `:app:verifyPackagedWeights` on both built APKs.

## [18.1] — 2026-11-20

### Changed

- **The depth stream no longer computes a second opinion about the book.** `LiveStreamTelemetry`
  carried a bid share and an imbalance that it calculated itself on every depth frame. Both
  lost their reader when `MicrostructureGuard` took over judging the book, so the socket now
  publishes the levels and their timestamp and nothing else — one place reads a balance out
  of a book, and the stream cannot disagree with it.

### Fixed

- **A refresh with no cached universe failed silently.** With nothing to fall back on, the
  app showed an empty dropdown and said nothing. It now reports `NO_DATA` as a failed
  refresh, and an empty screen whose venue answered keeps the distinction C-37 already made
  for the retained case: `NOTHING_QUALIFIED_NO_CACHE` says no coin passed the screen, so an
  empty list is never blamed on the network.

### Verification

- BDD specs: the stream-contract scenario in `MicrostructureGuardBddSpec` fails if the
  socket carries a share or imbalance again, and `ScreenedUniverseCacheBddSpec` covers both
  empty-with-no-cache outcomes plus the notice text for each.

## [18.0] — 2026-11-20

Milestone 3 of the Phase 2 work: the coin-selection boundary is real, the order book is
judged before it is quoted, and the retained-universe notice says what actually happened.

### Fixed

- **An in-flight evaluation overwrote the coin the user had just selected.** The cycle read
  `selectedSymbol`, evaluated it, and wrote the result back with `state.copy(...)`; nothing
  checked the symbol was still selected. A BTCUSDT evaluation that finished after an
  ETHUSDT selection replaced the new coin's price, chart, cone and verdict with the old
  coin's. The write is now refused unless `acceptsEvaluation` says the symbol is current.
- **Switching coins carried the previous asset's readings across.** `selectCoin` copied
  four prices and left the chart, forecast cone, verdict, brief, liquidity magnet,
  screeners, book readings, readiness badges and per-coin hit rates in place, so they read
  as if they described the new coin. `withSelection` clears them at the boundary.
- **A missing order book was evaluated as a perfectly balanced one.** The cycle and the
  depth stream both defaulted the bid share to `50.0`, so a coin whose depth call failed
  fed a fabricated 50/50 split into the regime agent, the router's L2 leg, the narrative
  and the depth panel. `MicrostructureGuard` refuses an undated, stale (>30 s), crossed or
  shallow (<10 levels) book and reports a null share; the UI prints "No order book" and the
  brief says the L2 leg carries no directional evidence.
- **The retained-universe snackbar blamed the network for the gate's verdict.** Keeping a
  cache after a run that screened 298 pairs and admitted none reported "Universe refresh
  failed". `NOTHING_QUALIFIED` now says no coin passed the screen; only a ticker that never
  answered reports a failed refresh.

### Verification

- BDD specs: `AssetSelectionBddSpec` (selection clears per-asset readings, a late
  evaluation for the previous coin is refused, and the production path keeps the guard),
  `MicrostructureGuardBddSpec` (absent / undated / stale boundary / crossed / shallow /
  healthy reading, and no `50.0` default left in either production path), plus the
  `NOTHING_QUALIFIED` scenarios in `ScreenedUniverseCacheBddSpec`; recorded as contracts
  C-38 and C-39, with C-37 amended for the two retention cases.

## [17.0] — 2026-11-20

Milestone 2 of the Phase 2 work: the universe screen is one gate instead of two, and the
list it produces survives a failed refresh and states its age.

### Fixed

- **The app admitted coins on one good day.** `runInstitutionalScreening` re-implemented
  the §65 four-filter gate inline and used `daysPassedAll > 0` as its persistence rule, so
a single historical pass admitted a coin the pinned contract requires to pass eight of
the last ten sessions. Both paths now call `ScreenedUniverseFilter`; the guard spec fails
the build if the repository grows its own thresholds again.
- **A missing open interest passed the velocity leg.** The screening path substituted
  `1.0` USDT for an open interest neither Binance nor CoinGlass returned, which every
  coin's volume cleared many times over. Unknown open interest is now 0.0 — the leg
  fails, because new money cannot be verified from a number the app invented.
- **A failed insert left no universe.** The cache was cleared before the replacement rows
  were inserted, as two statements. `ScreenedCoinDao.replaceAll` is one `@Transaction`,
  and the coin rows plus the state that describes them are written with
  `database.withTransaction`.
- **The cache could not say how old it was, or that it was the cache.** Room schema 11
  adds `screened_universe_state` (`fetchedAtMillis`, `screenedPairs`, `admittedPairs`) via
  the production 10 -> 11 migration. A run whose ticker never answered, or that admitted
  nothing, keeps the previous universe and reports it as cached with its age instead of
  presenting a tiny or empty list as today's screen.

### Changed

- **A resume inside ten minutes reuses the cached universe.** A warm launch used to
  re-run the 250-pair scan every time; a cache younger than `ScreenedUniverseRevalidation`
  `.TTL_MILLIS` is served as-is. A manual refresh always screens.

### Verification

- BDD specs: `ScreenedUniverseFilterBddSpec` (every threshold boundary and the 8-of-10
  rule), `ScreenedUniverseCacheBddSpec` (migration 10 -> 11, atomic swap, freshness and
  retention), two new scenarios in `UnifiedUniverseScreenerBddSpec`, and the upgraded
  `EnsembleWeightsMigrationBddSpec`; recorded as contract C-37.

## [16.0] — 2026-11-20

Milestone 1 of the Phase 2 work: an APK now updates in place, and an update no
longer costs the user their ledger or their 270 MB of TSFM weights.

### Fixed

- **Local builds were a version downgrade.** `versionCode` was `10_000 + run number`,
  which is `10_000` for every build made outside CI — lower than any published APK,
  so installing one failed with `INSTALL_FAILED_VERSION_DOWNGRADE`. Developer builds
  now live in a band above the CI band and both channels add the git commit count, so
  a rebuild of a newer commit is always newer. The count is read through
  `providers.exec`, which the configuration cache accepts.
- **A cold signing cache silently changed the app's identity.** Both keystores came
  from an Actions cache; on a miss the workflow generated a new certificate and only
  warned, so the next install failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The
  debug certificate is now committed (`debug.keystore.base64`) and pinned in
  `signing-fingerprints.txt`; the build job compares the keystore it is about to sign
  with to that pin and fails with `::error::` on a mismatch. The release key stays a
  secret and is pinned through the optional `RELEASE_CERT_SHA256` secret. The
  committed keystore is applied *after* the Actions cache restores an older copy —
  CI run 44725732530 proved that otherwise the cache wins and the build signs with a
  certificate installed debug builds reject.
- **Model weights could land in a cache directory and be trusted when corrupt.** The
  locator fell back to `cacheDir` when external storage was unavailable, and mount
  state was restored for any non-empty file. Weights now fall back to the persistent
  `filesDir`, and `init` re-verifies the pinned size and SHA-256, deleting a bad file
  so it is re-downloaded instead of mounted. An unchanged file is recognised by a
  `length:lastModified` token, so TimesFM's 261 MB is not re-hashed on every launch.

### Verification

- BDD specs: `SigningIdentityBddSpec`, `ModelPersistenceBddSpec`, and two new
  scenarios in `BuildContractBddSpec`; recorded as contract C-36.

## [15.1] — 2026-11-20

Completes the Phase 1 blueprint: the on-demand feature module is real, the catalogue
lives in Room and is queried by the decision engine, the ledgers are readable at phone
widths, and the core view is provably unmoved by installing the module.

### Added

- **On-demand `:proactive_trader` module (D4/§5)** — the Gradle project now splits into
  `:app`, `:core_contract` and `:proactive_trader`. The feature is declared with
  `dynamicFeatures`, depends on the base only in that direction, ships no native code
  or resources, exposes `dist:onDemand`, and hosts the six ProactiveMS tabs in
  `TraderHostActivity`. CI compiles it in preflight and proves the built `.aab`
  contains the `proactive_trader` module.
- **Verified dynamic tab injection (§7)** — `TraderNavigation` derives the tab from the
  entitlement lifecycle: absent before purchase, `Trader 40%` during download,
  `Installing` during install, inert until `INSTALLED`, then launched by component
  name. The unlock action is a single simulated Play billing sheet.
- **Room-backed Trade Ideas Catalog (D7)** — schema version 10 adds `trade_ideas`
  with every published baseline, seeded on fresh install and by the production 9 -> 10
  migration. `CatalogueRepository` maps the live Markov regime to a `Regime` and hands
  Needle 3 up to eight permitted candidates; an unavailable catalogue means no
  candidates, never a guessed taxonomy.
- **Pain path prior (§3)** — the spot card now publishes
  `Pain Path: Ascending Sweep to $23.84995 Overhead Stop Cluster (Pain Score: 61/100)`,
  derived from the order-book path drift and confluence confidence.
- **Pinned ledger columns (§2)** — the price and pattern ledgers pin ASSET/HORIZON and
  scroll the remaining columns on one shared state, so no header clips at 320-411dp.
- **Zero-regression proof (D6)** — a Compose layout spec asserts the core analysis
  surface and its content keep identical bounds before and after the trader tab is
  injected, and a structural spec asserts the core screen receives no trader state.

### Changed

- **ABI splits replaced by an explicit filter** — APK ABI splits and dynamic feature
  modules cannot coexist in AGP 9 (`Cannot find PROCESSED_RES output`), so the base
  module now packages arm64-only through `ndk.abiFilters` and names its APK
  `app-arm64-v8a-{debug,release}.apk` explicitly. The shipped artifact, its name, CI
  paths and the rollback workflow are unchanged; C-03 records the new mechanism.

### Verification

- BDD specs added: `DynamicFeatureContractBddSpec`, `TraderTabInjectionBddSpec`,
  `TraderZeroRegressionBddSpec`, `CataloguePersistenceBddSpec`, `PainPathPriorBddSpec`.
- Finalized contracts C-32 to C-35 record the four new decisions with their guards.

## [15.0] — 2026-11-19

Phase 1 of the Comprehensive Specification: the defect audit is fixed, the trade
ideas catalogue is executable, and the auto-trader's entitlement/download lifecycle is
modelled behind a module contract.

### Added

- **Tokenized decision rows (D1)** — `TokenizedReason` renders a verdict as
  `[N3: Long @ 0.50 < 0.70] Fallback: Rules`. Model, action, score, gate and fallback
  fit the 180dp REASON column, so nothing clips at 360dp and no number is pushed
  off-screen.
- **Neutral root-cause attribution (D2)** — a flat `DecisionCard` now explains
  itself: a confidence gate unmet, a directional conflict across the strategy board,
  or a quorum failure. "NEUTRAL" is never left unexplained.
- **Strategy conflict tags (D3)** — `StrategyConflicts` elects one dominant direction;
  opposing cards keep their place but carry an amber `[WATCH: CONFLICT]` badge and
  the note naming the drift they trade against.
- **§3 structural metadata** — cards print `Dist: 0.3 ATR · Zone: Fresh (Touch 1/3)`,
  a suspended setup reads `STATUS: Staging Suspended (Awaiting Trigger)` instead of
  empty dashes, and the price ledger's horizon filter is captioned by Tactical vs.
  Strategic intent.
- **SGD action tags** — `SGD Step: - w_penalty (Loss: 1.10)` is presented as
  `[PENALTY: Loss 1.10] Hanging Man confirmed`; reinforcement reads `[PRESERVED]`.
- **Trade Ideas Catalog (D7)** — `TradeIdeasCatalog` carries 44 setups across
  candlestick reversals, macro formations, indicator combinations and quant rules,
  each with its published win-rate/return baseline and permitted Markov regimes.
  `CatalogueWeights` is the learnable half: impulse within three candles reinforces,
  a false alarm decays, bounded so a setup is never retired.
- **Dynamic-trader contract (D4/D5)** — `:core_contract` publishes
  `DynamicTraderContract`/`ModuleInstallState`, and `SimulatedTraderManager` persists
  entitlement, reports honest 0→100% progress, separates INSTALLING from DOWNLOADING
  and refuses to launch the dashboard before installation.

### Known gaps

- `:proactive_trader` is **not** shipped as a dynamic feature in this release: the
  module's resource processing fails against the base app's `arm64-v8a` ABI split in
  AGP 9.1.1 (`Cannot find PROCESSED_RES output`). The contract and the simulated
  lifecycle are in place, so re-enabling the module is a packaging change, not a
  redesign. D6's pixel-diff harness therefore has nothing to compare yet.
- Catalogue rows are in-memory, not a Room table: the schema is pinned at v9 by
  C-22/C-23 and a new table needs its own migration + contract row.

## [14.9] — 2026-11-18

The data fix from 14.8 is now measured rather than argued: one simulated hour of the
ten-second cycle runs through the real caches and counts every REST request.

### Added

- **`CycleNetworkBudget`** — the cycle's intervals and payload assumptions in one
  place, with the hourly request count derived from them instead of hard-coded next
  to them. The repository consumes these constants, so a TTL edit cannot leave the
  budget behind.
- **`CycleNetworkBudgetBddSpec`** — walks 360 real `executeAssetEvaluation` calls over
  a fake clock (the new `clock` seam), records every request with a counting client,
  and asserts the hourly totals against the published table. It fails on a shortened
  TTL, a duplicate endpoint inside one cycle, the return of the REST `aggTrades` tape,
  or a payload over budget.
- **The budget:** 512 REST requests and ≈2.35 MiB per hour with the streamed book;
  872 requests and ≈4.52 MiB with the REST depth fallback. Ceilings are 520/900
  requests and 4.0/8.0 MiB. Mechanism, assumptions and the honest scope are in
  [`docs/ci-incidents.md`](docs/ci-incidents.md#data-budget-proven-offline--2026-11-18).

C-31 pins the budget and the guard spec; on-device traffic remains unmeasured here
(no device, and Binance geo-blocks this workspace), and the WebSocket streams are
deliberately outside the REST budget.

## [14.8] — 2026-11-18

One decisions table instead of two, ledgers that show what is still open, a forecast
cycle that stops re-downloading what has not changed, and verdicts that finally get
graded. Plus the CI forensics behind the four failed commits.

### Changed

- **The five decision rows are gone from the price ledger.** They duplicated the
  standalone Decisions Ledger a few centimetres below, so the price ledger is now only
  price predictions and `DecisionHistorySection` no longer exists.
- **The Pattern Prediction Ledger header matches the price ledger.** Same uppercase
  title, same check-circle mark, `Pend:`/`Settled:` counters on the first line and the
  hit rate beside the ledger buttons on the second, instead of a title-case label and a
  hit rate wedged into the counters.
- **All three ledgers hide settled rows by default.** Price, pattern and decisions
  ledgers share one `SHOW SETTLED` / `HIDE SETTLED` control (`LedgerSettledToggle`),
  and say how many settled rows are hidden when the pending list is empty.

### Fixed

- **Decisions never settled.** A verdict had no settlement of its own, so every row sat
  at "recorded" forever. `DecisionLedgerJoin` now grades each `DecisionRecordEntity`
  against the price prediction for the same symbol and reference horizon whose window
  contains the verdict, and the ledger shows `✓ HIT` / `✗ MISS` / `PENDING` with the
  realized price. No covering settled forecast means no credit (C-29).
- **Strategies stuck on FLAT.** The §3 library is daily-scale — Turtle's 20-bar high,
  Triple Screen's EMA20/50, Connors RSI's 10/90, CMF's ±0.05 — and it was being fed 500
  **1m** bars, eight hours of noise in which those thresholds cannot fire. The board now
  reads 500 **1h** bars (~21 days), so Triple Screen, CMF, Connors RSI and ORB have a bar
  long enough to carry the structure their rules describe.
- **~300 MB of data per hour.** The ten-second evaluation cycle re-downloaded data that
  only changes once a minute or once every few minutes (C-30): 1m klines are reused for
  60 s, the 240-minute liquidation heatmap for 5 minutes, the strategy series for the
  length of its bar, `aggTrades?limit=500` is no longer fetched on top of the live
  `aggTrade` stream that already carries the tape, and REST depth is only requested when
  no WebSocket book is up. A failed fetch is never cached, and a symbol never reuses
  another symbol's payload.

### CI

- Forensics for `4039b28`, `05fab5e`, `280198a` and `a00c0aa` are recorded in
  [`docs/ci-incidents.md`](docs/ci-incidents.md): three failed in gates that already
  existed (repository integrity, both test legs, pinned model sources), and `a00c0aa`
  shows the silent shape — the same R8 failure passed on `main` because the build step
  piped Gradle into `tee` without `pipefail` and nothing on `main` published an APK.

## [14.7] — 2026-11-18

The release that was not published: v1.0.20 built and verified its APKs, and the
GitHub Release job still refused to publish because the release APK did not exist.
The cause was a release build that had failed silently, so the pipeline now runs
the gates that would have caught it *first*, and can no longer pass on a variant
it did not produce. See [`docs/ci-incidents.md`](docs/ci-incidents.md).

### Fixed

- **The release APK could not be built (R8).** `:app:minifyReleaseWithR8` failed
  with "Missing classes detected while running R8": OkHttp names three optional
  TLS providers (BouncyCastle, Conscrypt, OpenJSSE) that this module does not
  ship, and R8 full mode treats the missing classes as a hard error. They are
  optional at runtime — OkHttp falls back to the platform TLS stack — so
  `proguard-rules.pro` now `-dontwarn`s exactly those packages. The release APK
  builds, shrinks and signs as before; no keep rule was added for classes that
  are not on the classpath.
- **A failed build step could report success.** The APK step pipes Gradle into
  `tee`; without `pipefail` the pipeline reported `tee`'s status, which is how a
  dead `assembleRelease` looked like "APKs built". The step now enables
  `pipefail` and asserts that every APK its `BUILD_TYPE` implies exists.
- **A missing APK became a skipped upload.** The release APK upload was gated on
  `hashFiles(<the APK itself>)`, so the absence turned into a skipped step and a
  green job. It is now gated on the requested build type and still fails on a
  missing file.

### Added

- **Preflight gates (C-28).** A `preflight` job runs before the APK build: the
  workflow contract (`scripts/check_ci_preflight.py`), both Kotlin source sets,
  and the release R8 pass. The `build` and `release` jobs depend on it, so a
  compilation or minification failure costs a minute instead of a full test
  matrix plus an NDK build.
- **CI contract checks.** `scripts/check_ci_preflight.py` and
  `build/CiGateIntegrityBddSpec.kt` fail when a blocking step pipes Gradle into
  `tee` without `pipefail`, when an APK upload self-skips, when the build step
  stops proving its artifacts, when the preflight wiring is removed, or when the
  newest changelog section (the published release body) is empty.
- **Incident record.** `docs/ci-incidents.md` documents why v1.0.18, v1.0.19 and
  v1.0.20 failed and which check now catches each cause first.

## [14.6] — 2026-11-18

The dual-ledger release: the price, pattern and decisions ledgers are all on the board,
the pattern ledger exports CSV with a hit rate, and the background engine actually holds
its wake lock, so locking the device no longer restarts the pipeline.

### Added

- **Decisions ledger.** Every recorded §29 verdict is listed in its own section instead
  of being capped at the newest five.
- **Pattern ledger export and hit rate.** The section header reports
  `Hit: <rate>% (<hits>/<rows>)` for the current horizon filter, and the export writes
  `timestamp,symbol,horizon,predictedPattern,realizedPattern,fitLoss,inCone`.

### Fixed

- **Background continuity (§24).** `AlphaPulseService` created its partial wake lock but
  never acquired it. It is now acquired and released only when the service is destroyed,
  and `START_STICKY` keeps the pipeline alive across a background or lock transition.
- **Pattern hit rate.** The rate read an `inCone` flag that nothing persisted, so it
  always showed 0%. A hit is now a settled row whose realized pattern matched its
  prediction — both values the row already stores.
- **Pattern ledger CSV export.** The export read the price-prediction flow (a plain
  `Flow` of a different table, with no `.value` to read) and used a C-style ternary, so
  the module did not compile. It now exports the pattern ledger the screen renders.
- **Ledger names and version banner.** The boards read "Price Prediction Ledger" and
  "Pattern Prediction Ledger", and the Pro Quant version string is gone from the header.

## [14.5] — 2026-11-17

Zero-bloat release: R8 minification enabled, all Firebase/Retrofit/moshi/logging
dependencies removed from the production APK, and only the two native boundaries
(ONNX Runtime and the JNI Needle 3 bridge) are kept via proguard-rules.pro.
The pattern ledger's `applyForecast` signature was simplified (the `forecast` param
was dropped) and `patternForecastFor` now private; `patternForecastFor` uses the same
mounted Chronos-Bolt / TimesFM ensemble as the price ledger, producing production-grade
forecast evidence without simulation.

### Added

- **R8 release shrinking (C-27).** Release builds now have `isMinifyEnabled = true` and
  `isShrinkResources = true` with `proguard-android-optimize.txt`; only `ai.onnxruntime.**`,
  `Needle3Jni`, and `native <methods>` are kept — a blanket application keep is forbidden.
- **Zero-external-dependency budget.** All Firebase BOM, `firebase.ai`, 
  `firebase.appcheck.*`, `converter-moshi`, `retrofit`, and `logging-interceptor` dependencies
  were removed from `app/build.gradle.kts`. The only production dependencies are
  `androidx.biometric`, `androidx.security:security-crypto` (CredentialStore), and the
  platform compose BOM. Every removed library was verified to have no remaining source
  reference — the `BuildContractBddSpec` guard asserts this.
- **`applyForecast` param simplification.** The `forecast` parameter was dropped from
  `PatternLedgerEngine.applyForecast(match, aligned)` since it was only used to check
  `forecast.available`; the evidence is now produced upstream by `patternForecastFor`.
- **`patternForecastFor` made private.** The helper in `AlphaPulseRepository` is now
  `private fun patternForecastFor` — it is only called from `runPatternLedger` within the
  same module.
- **C-27 traceability row in FINALIZED_CONTRACTS.md.** Documents the shrinking and
  dependency-budget guards, pinned to the `FinalizedContractGuardBddSpec` that fails the
  build when a blanket keep returns or an unreferenced SDK still ships.

### Changed

- **AlphaPulse tab scope (C-22).** The ProactiveMS campaign and live-order workspace is no
  longer mounted inside `AlphaPulseScreen`, and `MainActivity` no longer hands it the
  campaign view model. The tab renders AlphaPulse only: header, workspace cards, forecast
  accordions, price audit ledger, pattern ledger, §3 board and model dock. The guard spec
  fails the build if the crypto-trader boards return to this screen.
- **Contract registry** now records 27 decisions (C-01 … C-27); the pattern ledger's
  taxonomy count is corrected to the 27 shipped formations (including Evening Star).

### Fixed

- Pattern-ledger rows claimed an audited prediction without ever consulting the models the
  price ledger was using; both ledgers now share one inference pass and one clock, and the
  row states which models spoke.
- The §3 board was fed only the 40-bar forecast snapshot, so most models could never emit a
  signal. It now reads its own sufficient series and still abstains honestly when history is
  unavailable.
- **Crypto-Trader-Pro UI removed from AlphaPulse screen.** The `CampaignWorkspaceSection`
  and any ProactiveMS campaign board are no longer composable children of
  `AlphaPulseScreen`. The screen scope is AlphaPulse-only per C-22; a guard BDD in
  `ProactiveMsNavigationBddSpec` asserts exactly zero campaign references on this screen.

## [14.4] — 2026-11-17

The pattern ledger is now a production forecast ledger rather than a geometry-only table,
the §3 strategy board computes from enough real history to answer, the AlphaPulse tab no
longer hosts another product's screens, and a rollback is a real, versioned release.

### Added

- **TSFM ensemble in the pattern ledger (C-25).** Each pattern horizon now records the
  mounted **Chronos-Bolt** and/or **TimesFM** forecasts on the same inference pass that
  feeds the price ledger: `tsfmProvider`, `tsfmDriftPct` and `tsfmAligned` are persisted and
  shown in a new `TSFM ENSEMBLE` column. The formation itself remains the deterministic
  classifier's — settlement is graded against realized geometry — and the ensemble may only
  move the prior by the bounded `AGREEMENT_BONUS` / `CONTRADICTION_PENALTY` when its weighted
  median drift clears `MIN_DRIFT_PCT`. No model, a flat forecast or no drift leaves the row
  untouched and claims nothing.
- **Room schema v9.** The production 8 → 9 migration adds the three evidence columns with
  additive `ALTER TABLE` statements; the price ledger's `predictions` table is untouched.
- **Real history for the §3 board (C-24).** The screeners and nine quant models now evaluate
  a dedicated 500-bar 1m series from the same Binance `/fapi/v1/klines` source the price
  ledger and audit calibration use, refreshed at most once per bar. MACD (36 bars), Triple
  Screen (52) and the 60-bar breakout can finally compute instead of reporting
  “Needs 52 bars”. A failed fetch reuses the cached series for the same symbol, then the
  snapshot's own bars; no synthetic candle is ever fabricated.
- **Session-anchored Opening Range Breakout.** ORB now takes the first 15 bars of the
  current UTC session, so a longer window cannot mistake yesterday's range for today's open.
- **Rollback pipeline (C-26).** `.github/workflows/rollback.yml` rebuilds an existing
  verified `v*` tag at a higher `versionCode`, proves both arm64-v8a APKs and the pinned
  Needle 3 checkpoint, and publishes it as the latest release. No force-push, no moved tag,
  no uninstall for users.
- **Changelog automation.** Release bodies are composed from this file, and
  `RollbackWorkflowBddSpec` guards the rollback strategy the same way the other contracts
  are guarded.

### Changed

- **AlphaPulse tab scope (C-22).** The ProactiveMS campaign and live-order workspace is no
  longer mounted inside `AlphaPulseScreen`, and `MainActivity` no longer hands it the
  campaign view model. The tab renders AlphaPulse only: header, workspace cards, forecast
  accordions, price audit ledger, pattern ledger, §3 board and model dock. The guard spec
  fails the build if the crypto-trader boards return to this screen.
- **Contract registry** now records 26 decisions (C-01 … C-26); the pattern ledger's
  taxonomy count is corrected to the 27 shipped formations (including Evening Star).

### Fixed

- Pattern-ledger rows claimed an audited prediction without ever consulting the models the
  price ledger was using; both ledgers now share one inference pass and one clock, and the
  row states which models spoke.
- The §3 board was fed only the 40-bar forecast snapshot, so most models could never emit a
  signal. It now reads its own sufficient series and still abstains honestly when history is
  unavailable.

## [14.3] — 2026-10-30

### Added

- **Prediction Audit Ledger settlement and online SGD:** every issued forecast settles at its
  phase-locked boundary, records realized price, deviation, cone hit/breach and the learning
  action, and trains the Tri/Quad/Penta ensemble weights.
- **On-device TSFM inference (ONNX Runtime):** mounted Chronos-Bolt and TimesFM artifacts
  execute on-device; each contributes its own median forecast and quantile bands, and a
  missing or failed artifact is omitted rather than replaced by a synthetic drift.
- **§71/§72 unified audit ledger and workspace state:** 25-field ProactiveMS invalidation
  audit rows (Room v6 → v7) and one shared symbol across the seven-tab workspace.
- **Pattern Prediction & Learning Ledger:** 27 formations across five horizons, frozen
  predictions, cross-entropy grading against realized geometry and one online SGD step per
  settlement (Room v7 → v8).
- **§3 auxiliary library:** seven confluence screeners and nine quantitative models (Turtle,
  Dual Thrust, ORB, SuperTrend, Donchian, Connors RSI, VWAP reversion, CMF, Triple Screen),
  each returning its own entry, exit and invalidation levels.

### Changed

- **Artifact transports:** APK uploads keep `if-no-files-found: error` but tolerate GitHub's
  artifact-storage quota; a tag additionally stashes both verified APKs in the Actions cache
  and the release job refuses to publish unless both files exist.
- **Strict 5-decimal price precision** across every surface and export.

## [14.0 – 14.2] — 2026

- Native Android port of the PRO QUANT workstation: 3-stage institutional screener, Tri-Factor
  cone forecasts on 1m/5m/15m/30m/1h, Level-2 microstructure, FRVP/VWAP confluence, Markov /
  GARCH regime and the on-device Needle 3 decision router with the deterministic fallback.
- Persistent foreground engine, Room persistence and the 4-tab universe audit export.
- Finalized-contract guard (`FinalizedContractGuardBddSpec` + `scripts/check_integrity.py`),
  BDD-first change process and the CI pipeline that runs integrity → tests (API 33/34) →
  APK assembly + packaged-weights verification → release.

Zero-bloat release: R8 minification enabled, all Firebase/Retrofit/moshi/logging
dependencies removed from the production APK, and only the two native boundaries
(ONNX Runtime and the JNI Needle 3 bridge) are kept via proguard-rules.pro.
The pattern ledger's `applyForecast` signature was simplified (the `forecast` param
was dropped) and `patternForecastFor` now private; `patternForecastFor` uses the same
mounted Chronos-Bolt / TimesFM ensemble as the price ledger, producing production-grade
forecast evidence without simulation.

### Added

- **R8 release shrinking (C-27).** Release builds now have `isMinifyEnabled = true` and
  `isShrinkResources = true` with `proguard-android-optimize.txt`; only `ai.onnxruntime.**`,
  `Needle3Jni`, and `native <methods>` are kept — a blanket application keep is forbidden.
- **Zero-external-dependency budget.** All Firebase BOM, `firebase.ai`, 
  `firebase.appcheck.*`, `converter-moshi`, `retrofit`, and `logging-interceptor` dependencies
  were removed from `app/build.gradle.kts`. The only production dependencies are
  `androidx.biometric`, `androidx.security:security-crypto` (CredentialStore), and the
  platform compose BOM. Every removed library was verified to have no remaining source
  reference — the `BuildContractBddSpec` guard asserts this.
- **`applyForecast` param simplification.** The `forecast` parameter was dropped from
  `PatternLedgerEngine.applyForecast(match, aligned)` since it was only used to check
  `forecast.available`; the evidence is now produced upstream by `patternForecastFor`.
- **`patternForecastFor` made private.** The helper in `AlphaPulseRepository` is now
  `private fun patternForecastFor` — it is only called from `runPatternLedger` within the
  same module.
- **C-27 traceability row in FINALIZED_CONTRACTS.md.** Documents the shrinking and
  dependency-budget guards, pinned to the `FinalizedContractGuardBddSpec` that fails the
  build when a blanket keep returns or an unreferenced SDK still ships.

### Changed

- **AlphaPulse tab scope (C-22).** The ProactiveMS campaign and live-order workspace is no
  longer mounted inside `AlphaPulseScreen`, and `MainActivity` no longer hands it the
  campaign view model. The tab renders AlphaPulse only: header, workspace cards, forecast
  accordions, price audit ledger, pattern ledger, §3 board and model dock. The guard spec
  fails the build if the crypto-trader boards return to this screen.
- **Contract registry** now records 27 decisions (C-01 … C-27); the pattern ledger's
  taxonomy count is corrected to the 27 shipped formations (including Evening Star).

### Fixed

- Pattern-ledger rows claimed an audited prediction without ever consulting the models the
  price ledger was using; both ledgers now share one inference pass and one clock, and the
  row states which models spoke.
- The §3 board was fed only the 40-bar forecast snapshot, so most models could never emit a
  signal. It now reads its own sufficient series and still abstains honestly when history is
  unavailable.
- **Crypto-Trader-Pro UI removed from AlphaPulse screen.** The `CampaignWorkspaceSection`
  and any ProactiveMS campaign board are no longer composable children of
  `AlphaPulseScreen`. The screen scope is AlphaPulse-only per C-22; a guard BDD in
  `ProactiveMsNavigationBddSpec` asserts exactly zero campaign references on this screen.

## [14.4] — 2026-11-17

The pattern ledger is now a production forecast ledger rather than a geometry-only table,
the §3 strategy board computes from enough real history to answer, the AlphaPulse tab no
longer hosts another product's screens, and a rollback is a real, versioned release.

### Added

- **TSFM ensemble in the pattern ledger (C-25).** Each pattern horizon now records the
  mounted **Chronos-Bolt** and/or **TimesFM** forecasts on the same inference pass that
  feeds the price ledger: `tsfmProvider`, `tsfmDriftPct` and `tsfmAligned` are persisted and
  shown in a new `TSFM ENSEMBLE` column. The formation itself remains the deterministic
  classifier's — settlement is graded against realized geometry — and the ensemble may only
  move the prior by the bounded `AGREEMENT_BONUS` / `CONTRADICTION_PENALTY` when its weighted
  median drift clears `MIN_DRIFT_PCT`. No model, a flat forecast or no drift leaves the row
  untouched and claims nothing.
- **Room schema v9.** The production 8 → 9 migration adds the three evidence columns with
  additive `ALTER TABLE` statements; the price ledger's `predictions` table is untouched.
- **Real history for the §3 board (C-24).** The screeners and nine quant models now evaluate
  a dedicated 500-bar 1m series from the same Binance `/fapi/v1/klines` source the price
  ledger and audit calibration use, refreshed at most once per bar. MACD (36 bars), Triple
  Screen (52) and the 60-bar breakout can finally compute instead of reporting
  “Needs 52 bars”. A failed fetch reuses the cached series for the same symbol, then the
  snapshot's own bars; no synthetic candle is ever fabricated.
- **Session-anchored Opening Range Breakout.** ORB now takes the first 15 bars of the
  current UTC session, so a longer window cannot mistake yesterday's range for today's open.
- **Rollback pipeline (C-26).** `.github/workflows/rollback.yml` rebuilds an existing
  verified `v*` tag at a higher `versionCode`, proves both arm64-v8a APKs and the pinned
  Needle 3 checkpoint, and publishes it as the latest release. No force-push, no moved tag,
  no uninstall for users.
- **Changelog automation.** Release bodies are composed from this file, and
  `RollbackWorkflowBddSpec` guards the rollback strategy the same way the other contracts
  are guarded.

### Changed

- **AlphaPulse tab scope (C-22).** The ProactiveMS campaign and live-order workspace is no
  longer mounted inside `AlphaPulseScreen`, and `MainActivity` no longer hands it the
  campaign view model. The tab renders AlphaPulse only: header, workspace cards, forecast
  accordions, price audit ledger, pattern ledger, §3 board and model dock. The guard spec
  fails the build if the crypto-trader boards return to this screen.
- **Contract registry** now records 26 decisions (C-01 … C-26); the pattern ledger's
  taxonomy count is corrected to the 27 shipped formations (including Evening Star).

### Fixed

- Pattern-ledger rows claimed an audited prediction without ever consulting the models the
  price ledger was using; both ledgers now share one inference pass and one clock, and the
  row states which models spoke.
- The §3 board was fed only the 40-bar forecast snapshot, so most models could never emit a
  signal. It now reads its own sufficient series and still abstains honestly when history is
  unavailable.

## [14.3] — 2026-10-30

### Added

- **Prediction Audit Ledger settlement and online SGD:** every issued forecast settles at its
  phase-locked boundary, records realized price, deviation, cone hit/breach and the learning
  action, and trains the Tri/Quad/Penta ensemble weights.
- **On-device TSFM inference (ONNX Runtime):** mounted Chronos-Bolt and TimesFM artifacts
  execute on-device; each contributes its own median forecast and quantile bands, and a
  missing or failed artifact is omitted rather than replaced by a synthetic drift.
- **§71/§72 unified audit ledger and workspace state:** 25-field ProactiveMS invalidation
  audit rows (Room v6 → v7) and one shared symbol across the seven-tab workspace.
- **Pattern Prediction & Learning Ledger:** 27 formations across five horizons, frozen
  predictions, cross-entropy grading against realized geometry and one online SGD step per
  settlement (Room v7 → v8).
- **§3 auxiliary library:** seven confluence screeners and nine quantitative models (Turtle,
  Dual Thrust, ORB, SuperTrend, Donchian, Connors RSI, VWAP reversion, CMF, Triple Screen),
  each returning its own entry, exit and invalidation levels.

### Changed

- **Artifact transports:** APK uploads keep `if-no-files-found: error` but tolerate GitHub's
  artifact-storage quota; a tag additionally stashes both verified APKs in the Actions cache
  and the release job refuses to publish unless both files exist.
- **Strict 5-decimal price precision** across every surface and export.

## [14.0 – 14.2] — 2026

- Native Android port of the PRO QUANT workstation: 3-stage institutional screener, Tri-Factor
  cone forecasts on 1m/5m/15m/30m/1h, Level-2 microstructure, FRVP/VWAP confluence, Markov /
  GARCH regime and the on-device Needle 3 decision router with the deterministic fallback.
- Persistent foreground engine, Room persistence and the 4-tab universe audit export.
- Finalized-contract guard (`FinalizedContractGuardBddSpec` + `scripts/check_integrity.py`),
  BDD-first change process and the CI pipeline that runs integrity → tests (API 33/34) →
  APK assembly + packaged-weights verification → release.
