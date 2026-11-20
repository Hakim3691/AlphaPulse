# Changelog

All notable changes to AlphaPulse are recorded here. The GitHub Release for each `v*` tag
publishes the newest section of this file, so the release page and the repository never
disagree about what shipped.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the app's
own `versionName` (`1.0.<versionCode>+<sha>`), which is stamped by CI.

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
