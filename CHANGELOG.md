# Changelog

All notable changes to AlphaPulse are recorded here. The GitHub Release for each `v*` tag
publishes the newest section of this file, so the release page and the repository never
disagree about what shipped.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the app's
own `versionName` (`1.0.<versionCode>+<sha>`), which is stamped by CI.

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
