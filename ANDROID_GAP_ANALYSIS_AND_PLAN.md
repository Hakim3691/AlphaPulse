# AlphaPulse Android — Gap Analysis & Remediation Plan

**Baseline analyzed:** HTML5 SPA PRO QUANT V14.3 → current native port on `main`
**Reference documents:** `AlphaPulse_Android_SPEC_V14-3.md` (target spec, 64 requirements + Appendix A), `AlphaPulse_Native_Android_PRD.md`, `AlphaPulse_HTML5_SPEC/IMPL_V14-3.md` (parity baseline), `implementation_plan.md` (V14.3 parity scope)
**Current code:** `app/src/main/java/com/example/**` (~5,200 LOC Kotlin/Compose, 23 files)

---

## 1. Where the app sits today

The repo docs define a **four-release roadmap** (Android SPEC §62):

| Release | Theme | Status in current code |
|---|---|---|
| R1 — Native Parity | Screening, Tri-Factor, L2, ledger, service, Room, CSV | **~85% done** — parity UI, 3-stage pipeline, WS depth/aggTrade, foreground service, 2 Room entities, 4-tab CSV. Gaps below. |
| R2 — Quant Upgrade | Full order-book reconstruction, liquidation heatmap, trade tape, horizon pain paths, adaptive FRVP, neural UQ-LOB/AGA-HMM | **Not started** (only L2-proxy and smartmoneyapi stand-ins exist) |
| R3 — Multi-Model Intelligence | TSFM fourth factor, model manager/benchmarking, per-coin/per-horizon learning | **Not started** |
| R4 — Edge Intelligence | Edge SLM, decision router, agent DAG, disagreement engine, notifications, observability | **Not started** (rule-based `QuantNarrativeAgent` only) |

**Verdict:** the current app is an R1 "SPA port," not yet the intended Android workstation. The docs' core thesis (§64) is unimplemented: *native Quant Core + deep microstructure + independent TSLM/TSFM layer + adaptive ensemble + decision gate + SLM narrator*.

---

## 2. HTML5 compromises that survived the Android port

These are the exact items the PRD says were compromises in the SPA that the Android build must eliminate — still present in code:

| # | SPA compromise | Where it lives today | Spec reference |
|---|---|---|---|
| 1 | **Top-20 depth snapshot only** (`@depth20@250ms` stream not a reconstructed book) | `BinanceWebSocketManager` — depth tiers consumed as-is | FR-006 §7 |
| 2 | **500-trade REST snapshot** for the institutional shock instead of a rolling tape | `fetchAggTrades(limit=500)` | FR-008 §10 |
| 3 | **Single exponential pain path** `μp·exp(-λt)` blended heuristically per horizon instead of a real Horizon-Aware Pain Path Engine | `QuantMath.computeHorizonPainPath` (linear blends of L2/POC/VA triggers) | §8 |
| 4 | **`10^5` gradient multiplier SGD** — the exact SPA hack | `AlphaPulseRepository.auditAndSelfTrain` (line: `val gradientMultiplier = 100000.0`) | §20 |
| 5 | **Single generic weight state** — not per-coin/per-horizon; reset to defaults (`ModelState()`) when user selects a different coin | `AlphaPulseViewModel.selectCoin` (`// Reset per asset`); `ModelState` is in-memory only, never persisted | §20 |
| 6 | **Fixed 50-bin volume profile** | `QuantMath.computeVolumeProfileAndVWAP` | FR-012 |
| 7 | **Kurtosis-threshold Markov approximation** instead of AGA-HMM neural model | `evaluateMarkovRegimeGARCH` | §14 |
| 8 | **UQ-LOB heuristic** (spread-skew proxies) instead of a neural order-book uncertainty model | `computeUQLOBUncertainty` | §13 |
| 9 | **Liquidation heatmap via unverifiable third-party URL** (`api.smartmoneyapi.com`) instead of building a local heatmap from Binance `!forceOrder@arr` streams | `ResilientBinanceClient.fetchLiquidityHeatmap` | FR-007 §9 |
| 10 | **Device-clock-only candle locking** (round-minute arithmetic, no NTP/exchange-time offset, no clock-error state) | repository scheduling + service notifications | §23 |
| 11 | **Lightweight ledger schema** — 25 columns vs. the ~40-field spec ledger (no per-model forecasts, no weights snapshot, no regime/LOB/TSFM confidence, no prediction state machine, no model version) | `PredictionEntity` | §21, §34 |
| 12 | **Room DB with 2 tables** vs. ~17 required, no retention policy, no sequence-integrity columns | `AppDatabase` | §37 |
| 13 | **Tape buffer lost on coin switch / restart** (in-memory only, no session recovery of order-book or trade state) | `BinanceWebSocketManager`, `ViewModel` | §54 |
| 14 | **Binance-only**; no `ExchangeDataProvider` abstraction, no Bybit/Hyperliquid | `ResilientBinanceClient` hard-coded paths | FR-004/005, §2 |

## 3. Additional structural gaps (whole subsystems absent)

- **Testing:** only scaffold `ExampleUnitTest.kt` / `ExampleRobolectricTest.kt`. Spec §52–53 requires unit/integration/quantitative/resilience suites plus an explicit lock-screen lifecycle test. TDD is a stated requirement.
- **Security:** Android Keystore / biometric credential storage unused (only the `.env`/secrets-gradle placeholder). §42–43.
- **Data quality engine** (§35), **prediction state machine** (§34), **model versioning** (§55), **confidence decomposition** (§33), **model health + disagreement** (§31–32): absent.
- **Battery/mode profiles** (§46), **offline mode** (§36), **WorkManager** jobs (§25), **model manager/registry** (§47–48, A.9): absent.
- **Multi-format export** (CSV/JSON/Parquet/Share; user-initiated; §38): CSV-only, append-only.
- **Package hygiene:** code lives in `com.example.*` while `applicationId` is `com.aistudio.alphapulse.kxmpzq`; research docs reference `com.aistudio.alphapulse.*`. Naming should be normalized before the entity/schema migration (moving entities later doubles migration cost).
- **Observability screen** (§56), **notification engine with veto** (§57–58): absent.

---

## 4. Remediation plan

Ordered by the spec's own release strategy, with each phase gated by verification. Architecture-first items that unblock later phases are pulled forward.

### Phase 0 — Foundation hardening (prerequisite, small)
Non-demo work that everything else depends on.

1. **Package normalization** — `com.example.*` → `com.aistudio.alphapulse.*` (keep `applicationId`; update CI workflow, manifest, Room schemas).
2. **Deterministic core extraction** — move `QuantMath` + screening math into a pure `domain/` module with zero Android imports, so the quantitative suites of §52 can be plain JVM unit tests. The math is currently coupled only through `DepthTier/Kline` — mostly done, seal it.
3. **DI / composition seam** — introduce a small manual container (no Hilt required) so network, DB, engine, and service are injected rather than constructed ad-hoc in `ViewModel`/`Service`. Required for R2+ multi-exchange swap and for Robolectric integration tests.
4. **Test scaffolding** — set up JUnit5/Robolectric fixtures, fixture Binance JSON payloads, `MockWebServer` + `MockWebSocketListener`.

*Exit criteria:* `./gradlew :app:testDebugUnitTest` runs a real suite; package rename compiles; service/VM built via container.

### Phase 1 — Complete Release 1 "Native Parity" fixes
Close the gaps the docs themselves declare part of parity.

1. **Persisted ensemble state** — new `EnsembleWeightsEntity` keyed `(symbol, horizon)`; seed via existing `calibrateInitialWeightsFromHistory` (per symbol × horizon, not one global); remove the "reset per asset" reset. *(Spec §20 partial: persistence now; optimizer upgrade in Phase 4.)*
2. **NTP/exchange-time sync** — sync from exchange `serverTime` (Binance) with periodic re-check; maintain `clockOffset`; schedule forecast issuance and validation on corrected time; add `PENDING_DATA / CLOCK_ERROR` prediction states. *(§23, §34)*
3. **Prediction state machine** — add `status` column: `CREATED → ACTIVE → TARGET_REACHED → VALIDATING → SETTLED → LEARNING_APPLIED` (+ `INVALIDATED / DATA_GAP / PENDING_DATA / MODEL_FAILURE / CLOCK_ERROR`); never delete rows (remove `clearBySymbol/clearAll` semantics or gate behind explicit user export-first flow, §21).
4. **Session recovery** (§54) — on service start after process death: reload Room state, recover weights, rebuild market state, resume; add `Session` table. Verify with a Robolectric restart-of-process test.
5. **Lifecycle test** (§53): scripted start → lock → 30 min (virtual time) → unlock → all scheduled predictions validated, ledger complete.
6. **Ledger schema upgrade v1** — extend `PredictionEntity` to spec §21 for the three existing factors (fields for per-factor forecast, weights snapshot, regime confidence, prediction source/model version). Full four-model fields arrive in Phase 4 but the migration pattern is established here.
7. **Auto-save + export confirmation** — verify the 4-tab CSV writes on screening completion (spec §38 wants exports user-initiated; keep auto-save as a setting, default on, matching V14.3 behavior documented in README).

*Exit criteria:* weights survive app restart and coin switching; predictions settle correctly across lock/restart; parity feature checklist green.

### Phase 2 — Release 2 "Quant Upgrade" (the big microstructure rewrite)
1. **Order book reconstruction** (FR-006) — subscribe `@depth@100ms` diff + `@bookTicker`; maintain seq-number-validated `OrderBookState` with re-sync on gap; implement `LiquidityShelf`/`LiquidityVoid` detection (absorption, stacking, rapid cancellation, replenishment, depth-collapse); persist thresholds to Room for diagnostics; support configurable depth levels 20–1000 within rate limits. Failure fallback: keep current snapshot stream and flag it in UI ("L2 SNAPSHOT FALLBACK").
2. **Rolling trade tape** (FR-008) — replace 500-trade REST with `@aggTrade` WS → Room `Trades` table (ring-buffer retention per §37 configurable retention); compute per-horizon `InstitutionalShock(1m…1h)`, p95/p99 trade size, taker imbalance, burst intensity.
3. **Liquidation engine** (FR-007) — subscribe `!forceOrder@arr`; bins by price level with configurable bucket width, persist `Liquidations`; compute `Attraction(P) = LiqVolume(P)/Distance(Spot,P)`, nearest magnet + dominant shelves + asymmetry; horizon-specific liquidation targets. Delete the `smartmoneyapi.com` stand-in (keep it behind a premium-key flag at most).
4. **Horizon-Aware Pain Path Engine** (§8) — replace the heuristic blends: each horizon's μp computed from liquidity actually reachable within that horizon's expected volatility range (1m: immediate L2; 5m: near-book; 15m: intraday shelves; 30m: liquidation bands; 1h: macro/session extremes + VA rotation). Keep old formulas as fallback engine, expose active engine.
5. **Adaptive volume profile** (§12) — Freedman-Diaconis bin width with min/max clamps; compute POC/VAH/VAL/HVN/LVN/anchored VWAP/70% value area; persist `VolumeProfile` history; keep the 80% rotation rule.
6. **Room migration v2** — add required tables now in use: `IntradayKlines, Trades, OrderBookSnapshots/Deltas, Liquidations, FundingHistory, OpenInterestHistory, VolumeProfile, DataHealth, Session` with the spec's indexes.
7. **Data quality engine** (§35) — connectivity, candle continuity, book/trade sequence integrity, staleness age; expose `DATA HEALTH %` in the UI; gate new forecasts on freshness (feeds state machine `DATA_GAP`).
8. **Multi-exchange abstraction** (FR-005) — define `ExchangeDataProvider` interface; `BinanceProvider` implements it from the existing client; `BybitProvider` as first secondary adapter (REST + WS liquidation/depth), `HyperliquidProvider` stub. Engine code stops calling `fapi/v1` paths directly.

*Exit criteria:* reconstructed book survives 24h soak with zero unverified seq gaps; tape-based InstitutionalShock matches multi-hour p95 of empirical aggTrades; liquidation map persists across restart; parity features still pass.

### Phase 3 — Release 3 "Multi-Model Intelligence" (TSFM layer)
1. **Pluggable model interface** — `TimeSeriesForecastProvider` with quantile outputs (P10/P25/P50/P75/P90 → expected price/cone bounds/dispersion/skew, §18); structured market-state feature tensor (§17) even when a model consumes only close+volume, preserving full state for fusion.
2. **Inference backend abstraction** (§26–27) — `InferenceBackend` (CPU/GPU/NNAPI-auto) atop **ONNX Runtime Mobile**; export Kronos-small (24.7M, primary, per spec §16/A.2) and **Chronos-Bolt Mini (21M)** checkpoints to ONNX; benchmark on a fixed validation set vs. CPU; report both parameter_count and deployment_weight_size (A.8).
3. **Fourth factor fusion** (§15) — `μ̂ = Wk·μk + Wp·μp + Wα·μα + Wtsfm·μtsfm + Δμbias`, ΣW = 1; extend ledger (ledger schema v2) to record per-model forecasts + weights so "which model was responsible?" is always answerable (§19).
4. **Model Manager / Registry** (§47–48, A.9) — models downloaded from pinned public repos, SHA-256 verified, staged via checksum → runtime-compatibility check → benchmark → activate; `ModelRegistry` Room table; failed downloads never disable the deterministic engine.
5. **AdaW-family online learning** (§20) — replace `10^5` SGD with standardized-feature **AdamW** (Kalman filter and Online Mirror Descent as pluggable alternates behind `WeightOptimizer`); keep inverse-RMSE init; select via offline walk-forward, not intuition.
6. **Walk-forward validation harness** (§50–51) — offline research mode; strict no-data-leakage enforcement (no info after T); metrics: MAE/RMSE/directional accuracy/cone coverage/calibration error/turnover sensitivity. This is also the tool that decides whether the TSFM actually adds value per §64's hard requirement.

*Exit criteria:* on-device Kronos inference ≤100 ms (target); walk-forward report shows per-symbol-per-horizon model skill; ensemble never silently degrades to TSFM dependence (fallback FR-44: TSFM unavailable → Tri-Factor).

### Phase 4 — Release 4 "Edge Intelligence"
1. **Edge SLM narrator** (§28, A.6) — Llama-3.2-1B 4-bit via ExecuTorch, structured-JSON input, explain/diagnose/compare only; hard constraint: cannot modify numbers (prompt-level refusal + output field validation); upgrade path Gemma-2-2B / Phi-3.5-mini per device profile. Gate on Model Manager readiness; optionally ship rule-based narrator as fallback (current `QuantNarrativeAgent`).
2. **Decision router** (§29) — `DecisionRouter` interface; deterministic rule router first (convergence + confidence + regime + uncertainty + disagreement → STRONG LONG…NO TRADE); every override logged with reason.
3. **Agent DAG** (§30) — explicit pipeline agents (Ingestion → Market-State → Quant Vector → TSFM → Router → Risk/Confidence → Narrator) with inputs/outputs/timeout/health/version/telemetry/fallback; no silent output corruption.
4. **Model health + disagreement engine** (§31–32) — per-model latency/MAE/RMSE/directional accuracy/cone hit rate dashboards; disagreement score reduces confidence and can veto notifications.
5. **Decomposable confidence score** (§33) — UI tap-through "Why this forecast?" attribution panel (§41).
6. **Notification engine** (§57–58) — high-priority convergence alerts + cone breach/regime transition/liquidity shock/data failure; **notification veto** with logged reason when LOB uncertainty extreme, disagreement high, freshness low, or required model missing.
7. **Battery modes** (§46) — Live / Balanced / Analysis / Offline (clear "LIVE DATA UNAVAILABLE" instead of stale-but-live-looking predictions); [§36] offline mode: historical analysis, stored ledger, backtesting.
8. **Observability screen** (§56) — WS/REST latency, freshness, dropped messages, seq gaps, CPU/memory/battery, inference + DB latency, model errors.
9. **Export expansion** (§38) — JSON + user-initiated Share for ledger/model performance/liquidation history (Parquet only if a viable Android encoder exists — else defer with rationale).

### Cross-cutting tracking concerns
- **Security (§42–43):** Android Keystore + biometric unlock for any private/premium API keys (Coinglass etc.); public market data stays keyless. Do this when the first private-data feature lands, not before.
- **Hygiene:** strip unused Firebase AI/AppCheck/Moshi deps or wire them deliberately; Moshi (already present) should replace ad-hoc `org.json` parsing in the resilient client once the provider abstraction lands.
- **CI:** extend `build-apk.yml` to publish the unit-test report and run the Rolectric lifecycle test in a job step.

---

## 5. Suggested execution order & effort profile

| Phase | Dependency | Rough LOC impact | Risk |
|---|---|---|---|
| 0 Foundation | none | ~1–1.5k (rename + container + tests) | Low; do first, cheap |
| 1 Parity completion | Phase 0 | ~800 (DB adds weights/session/state machine) | Low–Med (Room migrations) |
| 2 Quant upgrade | Phases 0–1 | ~3–4k (new engines + networking) | **High** — WS uptime, rate limits, Room churn |
| 3 TSFM | Phase 2 | ~2–3k + model ops | Med–High (ONNX export, device perf) |
| 4 Edge intelligence | Phase 3 | ~3k | Med |

**Recommended first milestone:** Phases 0 + 1 together — they turn the existing port from a "portfolio demo" into a stable platform where every subsequent engine change is verifiable by tests that already exist (spec §52's mandatory suites are mostly blocked today by `QuantMath` residing in the Android module and by having no DI seam).

**Two decisions to confirm before implementation:**
1. Package rename `com.example` → `com.aistudio.alphapulse` now (cheap) or accept the mismatch long-term? Recommended: now.
2. Keep auto-save CSV (README V14.3 behavior) vs. spec §38 "user-initiated unless explicitly configured otherwise"? Recommended: setting, default on.
