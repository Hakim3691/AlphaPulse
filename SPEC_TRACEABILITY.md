# AlphaPulse — Spec Traceability Matrix

Every requirement in [`AlphaPulse_Android_SPEC_V14-3.md`](AlphaPulse_Android_SPEC_V14-3.md)
appears below exactly once, with the code that implements it and the tests that
prove it.

**Why this file exists.** The specification is 64 sections long. Without a map,
nobody can tell whether "§32 Model Disagreement Engine" is finished or merely
mentioned in a roadmap string. This table is that map, and CI checks that it
still covers all 64 sections.

**Status key**

| Status | Meaning |
| :--- | :--- |
| **Done** | Implemented and covered by automated tests. |
| **Partial** | Core behaviour exists, but a listed sub-requirement is missing. |
| **Gap** | Not implemented yet. Tracked in the row itself. |

Paths are relative to `app/src/main/java/com/aistudio/alphapulse/` unless stated
otherwise. Test paths are relative to `app/src/test/java/com/aistudio/alphapulse/`.

| § | Requirement | Status | Implementation | Tests |
| :-- | :--- | :--- | :--- | :--- |
| 1 | Product Vision | Done | `MainActivity.kt`, `README.md` | — (documentation) |
| 2 | Core Design Principle | Done | `domain/data/ExchangeDataProvider.kt`, `data/network/*Provider.kt` | `domain/…` contract tests |
| 3 | Functional Requirements | Done | `domain/agent/Agent.kt`, `domain/decision/DecisionRouter.kt` | `domain/agent/AgentTest.kt` |
| 4 | Risk-Adjusted Universe Ranking | Done | `domain/quant/QuantMath.kt` (Calmar, Sharpe) | `domain/quant/QuantMathTest.kt` |
| 5 | Native Networking Layer | Done | `data/network/ResilientBinanceClient.kt`, `data/network/FailoverExchangeProvider.kt` | `data/network/…` tests |
| 6 | Real-Time Market Data Engine | Done | `data/network/BinanceWebSocketManager.kt` | `data/…` websocket tests |
| 7 | Full Order Book Reconstruction | Done | `domain/quant/QuantMath.kt` | `domain/quant/OrderBookAnalysisTest.kt` |
| 8 | Horizon-Specific Liquidity Pain Model | Done | `domain/quant/QuantMath.kt` | `domain/quant/QuantMathTest.kt` |
| 9 | Proprietary Liquidation Heatmap | Done | `data/repository/AlphaPulseRepository.kt`, `data/network/BinanceWebSocketManager.kt` | `domain/quant/QuantMathTest.kt` |
| 10 | Institutional Trade-Tape Analysis | Done | `domain/quant/TradeTapeAnalyzer.kt` (aggressor imbalance, large prints, sweeps, narration); `AlphaPulseRepository.institutionalTape()` reads the stored `trade_buffer` | `domain/quant/TradeTapeAnalyzerBddSpec.kt` |
| 11 | Existing Tri-Factor Engine | Done | `domain/quant/QuantMath.kt` | `domain/quant/QuantMathTest.kt` |
| 12 | Full Volume Profile (FRVP, VAH/VAL/LVN) | Done | `domain/quant/QuantMath.kt` | `domain/quant/QuantMathTest.kt` |
| 13 | Neural UQ-LOB | Done | `domain/ml/UqLobEngine.kt`, `domain/ml/UqLobModel.kt` | `domain/ml/UqLobEngineTest.kt` |
| 14 | AGA-HMM Upgrade | Done | `domain/quant/QuantMath.kt` | `domain/quant/QuantMathTest.kt` |
| 15 | New Fourth Forecasting Factor | Done | `domain/ml/*Provider.kt`, `domain/quant/QuantMath.kt` | `domain/ml/TsfmProviderTest.kt` |
| 16 | Recommended Time-Series Model Strategy | Done | `domain/ml/onnx/TsfmForecaster.kt`, `domain/ml/TsfmLayer.kt` | `domain/ml/onnx/ChronosBoltAdapterBddSpec.kt`, `TimesFmAdapterBddSpec.kt` |
| 17 | TSFM Input Representation | Done | `domain/ml/TsfmPrediction.kt` (`TsfmInput`), `domain/ml/onnx/TsfmForecaster.kt` | `domain/ml/TsfmProviderTest.kt` |
| 18 | Probabilistic Forecasting (P10–P90) | Done | `domain/ml/TsfmPrediction.kt`, `domain/ml/KronosProvider.kt` | `domain/ml/TsfmProviderTest.kt` |
| 19 | Independent Model Cross-Validation | Done | `data/db/Entities.kt` (`modelProvider`) | `domain/ensemble/EnsembleWeightingTest.kt` |
| 20 | Adaptive Ensemble Learning | Done | `domain/ensemble/EnsembleWeighting.kt`, `tdata/repository/AlphaPulseRepository.kt`, `domain/ensemble/CoinCalibration.kt` (30-day per-coin scoring: MAE/RMSE/directional accuracy, inverse-error W4/W5 split inside the mount budget, ΣW = 1.000), `domain/ensemble/CoinCalibrationPipeline.kt` (rolling-context evaluation over every horizon), `domain/decision/CoinCalibrationOrchestrator.kt` (Needle 3 chooses the horizon set; deterministic plan otherwise), `data/db/Daos.kt` + `data/db/Entities.kt` (`EnsembleWeightsEntity`, one replaceable row per symbol#horizon), `AlphaPulseRepository.calibrateCoin()` (fetch → score → persist), `ui/viewmodel/AlphaPulseViewModel.kt` (calibrates on selection and applies the reference-horizon weights) | `domain/ensemble/AdaptiveEnsembleLearningBddSpec.kt`, `domain/ensemble/CoinCalibrationBddSpec.kt` (weight rules, the real pipeline, and the Needle-3/deterministic dispatch), `data/repository/CoinCalibrationPersistenceBddSpec.kt` (over in-memory Room: one row per horizon, recalibration replaces rather than appends, two assets keep their own weights) |
| 21 | Prediction Audit Ledger | Done | `ui/components/PredictionLedgerSection.kt`, `data/db/Daos.kt`, `data/db/Entities.kt` (`DecisionRecordEntity`: one routed verdict per evaluated snapshot — state, calibrated confidence, answering router, reference horizon and the router's own reason — pruned to the newest 10,000 per symbol), `data/repository/DecisionAuditJoin.kt` (attributes each prediction row to the verdict recorded in its cycle, in CSV, JSON and the line-per-record export), `ui/components/DecisionHistorySection.kt` (the newest verdicts for the selected asset, read from the same table the export joins on) | `data/db/DecisionRecordDaoBddSpec.kt`, `data/repository/DecisionAuditJoinBddSpec.kt`, `ui/components/DecisionHistorySectionBddSpec.kt`, `data/repository/AssetEvaluationDecisionRecordBddSpec.kt` (drives `executeAssetEvaluation` itself over in-memory Room with exchange fixtures: one row per cycle, the UI's history Flow emitting it unaided, no cross-symbol leakage, append-not-replace; and for the checkpoint-backed producer, a scripted engine's route and calibrated score are persisted as `NEEDLE3` with that score, reach the history Flow unchanged, and two symbols under mixed provenance keep their own source) |
| 22 | Per-Horizon Readiness | Done | `data/repository/AlphaPulseRepository.kt`, `ui/viewmodel/AlphaPulseViewModel.kt` | `ui/…` readiness tests |
| 23 | Forecast Scheduling | Done | `data/repository/AlphaPulseRepository.kt`, `domain/ml/TsfmLayer.kt` | `domain/ml/TsfmLayerTest.kt` |
| 24 | Persistent Background Engine | Done | `service/AlphaPulseService.kt`, `domain/ml/ModelRuntime.kt` | `model/…` service tests |
| 25 | WorkManager | Done | `data/model/WorkManagerScheduler.kt` | `model/WorkManagerSchedulerTest.kt` |
| 26 | Android Hardware Acceleration | Done | `profiling/HardwareGatekeeper.kt`, `domain/ml/ModelRuntime.kt` | `profiling/HardwareGatekeeperBddSpec.kt` |
| 27 | CPU/GPU/NPU Work Allocation | Done | `domain/ml/ModelRuntime.kt`, `domain/ml/UqLobEngine.kt` | `domain/ml/UqLobEngineTest.kt` |
| 28 | Edge SLM | Done | `domain/narrative/EdgeSlmNarrativeLayer.kt` | `domain/narrative/NarratorConstraintValidatorBddSpec.kt` |
| 29 | Quantitative Decision Router | Done | `domain/decision/DecisionRouter.kt`, `domain/decision/Needle3DecisionRouter.kt` (on-device routing: one grammar-constrained tool per state, calibrated-confidence gate, deterministic fallback), `domain/agent/Agent.kt` (`DecisionRouterAgent` resolves the on-device router for the 7-agent pipeline and reports why a route was chosen), `domain/decision/DecisionModelCatalog.kt`, `ui/components/DecisionCard.kt` (the live verdict, its calibrated confidence and the router's reason on the dashboard), `DecisionSource` + `DecisionRecordEntity` (which router answered is recorded with every verdict and persisted to the §21 ledger) | `domain/decision/DecisionRouterTest.kt`, `domain/decision/Needle3DecisionRouterBddSpec.kt`, `domain/agent/DecisionRouterAgentBddSpec.kt`, `ui/components/DecisionCardBddSpec.kt`, `domain/decision/DecisionInputsAdapterBddSpec.kt` |
| 30 | Needle / Agent Orchestration | Done | `domain/agent/Agent.kt`, `domain/decision/DecisionModelCatalog.kt`, `domain/decision/Needle3Engine.kt` (run path), `domain/decision/Needle3Jni.kt` (JNI surface + bridge), `domain/decision/Needle3DecisionRouter.kt` (routing turns over the same engine), `domain/decision/CoinCalibrationOrchestrator.kt` (grammar-constrained calibration dispatch), `data/model/BundledModelInstaller.kt` (the APK-bundled checkpoint is verified and mounted with no network), `app/src/main/cpp/needle_jni.cpp` (JNI bridge), `app/src/main/cpp/CMakeLists.txt` (links the vendored engine per ABI), `app/src/main/cpp/engine/` (vendored `libneedle.a` for arm64/armv7 + `needle.h`), `profiling/HardwareGatekeeper.kt`, `ui/components/Needle3StatusBanner.kt` | `domain/agent/AgentTest.kt`, `DecisionModelCatalogBddSpec.kt`, `Needle3EngineBddSpec.kt`, `Needle3DecisionRouterBddSpec.kt`, `JniNeedle3BridgeBddSpec.kt`, `VendoredEngineArtifactBddSpec.kt`, `ui/components/Needle3StatusBannerBddSpec.kt`, `domain/ensemble/CoinCalibrationBddSpec.kt` (the engine picks the calibration horizon set; every other case falls back to the deterministic plan) |
| 31 | Model Ensemble Health | Done | `domain/ensemble/EnsembleWeighting.kt`, `domain/ml/ModelRuntime.kt` | `domain/ensemble/EnsembleWeightingTest.kt` |
| 32 | Model Disagreement Engine | Done | `domain/ensemble/ModelDisagreementDetector.kt` | `domain/ensemble/EnsembleWeightingTest.kt` |
| 33 | Forecast Confidence Score | Done | `domain/decision/DecisionRouter.kt` | `domain/decision/DecisionRouterTest.kt` |
| 34 | Prediction State Machine | Done | `domain/quant/PredictionStateMachine.kt` (ISSUED → SETTLED → LEARNED / EXPIRED / VOID, illegal moves rejected), wired into the settlement path in `AlphaPulseRepository` | `domain/quant/PredictionStateMachineBddSpec.kt` |
| 35 | Data Quality Engine | Done | `domain/quality/DataQualityEngine.kt` (one score + typed issues), consumed by the `NotificationVeto` report overload | `domain/quality/DataQualityEngineBddSpec.kt`, `service/NotificationVetoQualityBddSpec.kt` |
| 36 | Offline Mode | Done | `data/model/OfflineModeManager.kt` | `model/OfflineModeManagerTest.kt` |
| 37 | Room Database | Done | `data/db/AppDatabase.kt`, `data/db/Entities.kt`, `data/db/Daos.kt` | `data/db/…` DAO tests, `androidTest/UserJourneyBddTest.kt` |
| 38 | Export System (CSV) | Done | `data/repository/AlphaPulseRepository.kt` (`exportAuditLedgerCsv`, `generate4TabUniverseCsv`; the prediction exports carry four decision columns — state, confidence (`UNSCORED` when uncalibrated), answering router and reason), `data/repository/DecisionAuditJoin.kt` | `data/repository/DecisionAuditJoinBddSpec.kt` |
| 39 | User Interface | Done | `ui/AlphaPulseScreen.kt`, `MainActivity.kt` | `androidTest/UserJourneyBddTest.kt` |
| 40 | Primary Dashboard | Done | `ui/components/TopWorkspaceCards.kt`, `ui/components/HeaderBar.kt`, `ui/components/DecisionCard.kt`, `ui/components/DecisionHistorySection.kt` (live verdict plus the recorded history behind it) | `ui/components/ModelDockUiTest.kt`, `ui/components/DecisionCardBddSpec.kt`, `ui/components/DecisionHistorySectionBddSpec.kt` |
| 41 | Institutional Explanation Panel | Done | `ui/components/AccordionsSection.kt`, `domain/quant/QuantNarrativeAgent.kt` | `domain/…` narrative tests |
| 42 | Security Requirements | Done | `data/security/KeystoreCredentialStore.kt` | `security/…` tests |
| 43 | API Key Architecture | Done | `data/security/KeystoreCredentialStore.kt` (the only place a secret lives; no decision model ships or calls an API key) | `KeystoreCredentialStore` tests |
| 44 | Failure Handling | Done | `domain/decision/Needle3DecisionRouter.kt` (every refusal ends in the deterministic rules, never an exception), `data/network/ResilientBinanceClient.kt` | `domain/decision/Needle3DecisionRouterBddSpec.kt` |
| 45 | Performance Requirements | Done | `domain/decision/Needle3DecisionRouter.kt` (one on-device turn; no network call or timeout in the routing path) | `domain/decision/Needle3DecisionRouterBddSpec.kt` |
| 46 | Battery Requirements | Done | `data/model/BatteryModeManager.kt`, `data/model/WorkManagerScheduler.kt` | `model/BatteryModeManagerTest.kt` |
| 47 | Model Download Architecture | Done | `data/model/ModelArtifactSource.kt`, `data/model/ModelArtifactInstaller.kt`, `data/model/ModelDownloadStore.kt`, `data/model/BundledModelInstaller.kt` (a checkpoint shipped in `assets/` is copied once, digest-verified, and mounted with zero runtime download), `app/build.gradle.kts` (`bundleNeedle3Weights` fetches and verifies `needle3.cact` before packaging; `androidResources.noCompress` keeps `.cact`/`.onnx` stored so the engine maps instead of inflating); the engine is **compiled in, never downloaded** (`decision.bundledAtBuildTime`), so the dock offers the 34 MB checkpoint and no engine part | `data/model/ModelArtifactSourceContractTest.kt`, `profiling/HardwareGatekeeperBddSpec.kt`, `VendoredEngineArtifactBddSpec.kt`, `scripts/check_integrity.py` (vendored digest guard) |
| 48 | Model Selection Benchmark | Done | `domain/ml/ModelBenchmark.kt` (mean/p95 latency, throughput, error rate, eligibility floor of 10 samples, best-first ranking) | `domain/ml/ModelBenchmarkBddSpec.kt` |
| 49 | Critical Model Selection Principle | Done | `data/model/ModelManager.kt`, `profiling/HardwareGatekeeper.kt` | `profiling/HardwareGatekeeperBddSpec.kt` |
| 50 | Walk-Forward Validation | Done | `domain/validation/WalkForwardValidator.kt` (non-overlapping train/test folds; MAE, RMSE, MAPE, directional accuracy, signed error, cone coverage, calibration error, latency, stability, turnover sensitivity) | `domain/validation/WalkForwardValidatorBddSpec.kt` |
| 51 | No Data Leakage Requirement | Done | `domain/validation/LeakageGuard.kt` (all eight §51 streams, `requireNoLeakage` fails a run, `upto` trims a series), enforced inside every walk-forward fold | `domain/validation/LeakageGuardBddSpec.kt` |
| 52 | Testing Requirements | Done | Test suites under `app/src/test` and `app/src/androidTest` | `testing/TestingRequirements.kt`, `jacocoCoverageGate` |
| 53 | Android Lifecycle Testing | Done | Lifecycle behaviour in `service/AlphaPulseService.kt` | `model/AndroidLifecycleTest.kt` |
| 54 | Session Recovery | Done | `data/model/ModelManager.kt`, `data/db/Entities.kt` (`SessionEntity`) | `model/…` session tests |
| 55 | Model Versioning | Done | `data/model/VersionInfo.kt`, `data/db/Entities.kt` (`versionTag`) | `data/model/…` tests |
| 56 | Observability | Done | `ui/components/ObservabilityDashboard.kt` | `ui/…` observability tests |
| 57 | Notifications | Done | `service/AlphaPulseService.kt` | `model/…` service tests |
| 58 | Notification Veto | Done | `service/NotificationVeto.kt` | `service/NotificationVetoBddSpec.kt` |
| 59 | AI Narrator Requirements | Done | `domain/narrative/EdgeSlmNarrativeLayer.kt`; the Needle 3 run path returns a typed refusal instead of text whenever the engine or checkpoint cannot run, and only ever returns the model's own JSON answer | `domain/narrative/NarratorConstraintValidatorBddSpec.kt`, `domain/decision/Needle3EngineBddSpec.kt` |
| 60 | Research Mode | Done | `ui/viewmodel/AlphaPulseViewModel.kt` | `model/ResearchModeTest.kt` |
| 61 | Recommended Final Architecture | Done | `di/ServiceLocator.kt` | `ServiceLocatorRobolectricTest.kt` |
| 62 | Release Strategy | Done | `release/ReleaseStrategy.kt` | `release/ReleaseProgressBddSpec.kt` |
| 63 | Definition of Done | Done | `release/DefinitionOfDone.kt` | `androidTest/UserJourneyBddTest.kt` |
| 64 | Most Important Architectural Change | Done | `release/ArchitecturalChange.kt` | `release/ReleaseProgressBddSpec.kt` |

## Open gaps (reported, not hidden)

**Every one of the 64 sections is now implemented.** What follows are the honest
limits of what has been verified, rather than unimplemented requirements:

1. **§30 Needle 3 execution on x86_64** — the Cactus engine is vendored and linked
   for `arm64-v8a` and `armeabi-v7a` only, because Cactus publishes no x86_64
   engine for Android. On an x86_64 emulator the bridge compiles its refusal path,
   `Needle3Engine.status()` returns the typed `NotBundled` state, and the app keeps
   using the deterministic narrator (§59).

2. **§30 Needle 3 inference has not been executed on a device here** — this
   environment has no Android device and no arm64 host, so what is proven is: the
   exact published engine is vendored and linked (digests pinned in the catalog, in
   `VendoredEngineArtifactBddSpec` and in `scripts/check_integrity.py`), the arm64
   and armv7 shared libraries export all seven JNI entry points and all eleven
   Cactus symbols, the x86_64 one contains none, and the whole run path is proved
   against a scripted engine. The first real turn on hardware is still unverified.
§48, §50 and §51 were closed after the v1.0.5 release: `ModelBenchmark` measures
and ranks models with a minimum-evidence rule, `WalkForwardValidator` runs the §50
train/predict/observe loop over non-overlapping folds and reports every metric §50
lists, and `LeakageGuard` makes look-ahead a hard failure enforced inside each fold
rather than a convention.

§10 and §35 were previously partial and are now complete: the trade tape has an
analyzer reading the stored buffer, and data quality has one engine whose report
the notification veto now consumes directly.

Microsoft-Decision-1 was removed. §29's decision routing now runs on device through
Cactus Needle 3 (`Needle3DecisionRouter`): the six decision states are six
grammar-constrained tools, the engine's calibrated confidence gates the answer, and
every refusal - no checkpoint, no engine, a suppressed call, an ungrounded
argument, low confidence - falls back to the deterministic rules. No endpoint, API
key, network timeout or configuration banner remains.

§30's remaining gap was closed after v1.0.6. The Cactus engine is no longer a
promise in a catalog: `android-arm64/libneedle.a` (2,177,676 bytes) and
`android-armv7/libneedle.a` (1,700,760 bytes) plus `needle.h` are vendored under
`app/src/main/cpp/engine/` with the digests Hugging Face publishes, CMake links
them into `libalphapulse_needle.so` for the ABI being built, and
`Needle3Engine` drives `needle_load` → `needle_init` → `needle_complete` through
JNI over an mmap'd checkpoint. `Needle3StatusBanner` shows the engine's state on
the dashboard, and the mounted checkpoint path now reaches the engine from the
same store the dock reads.
