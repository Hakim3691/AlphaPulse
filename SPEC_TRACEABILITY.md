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
| 20 | Adaptive Ensemble Learning | Done | `domain/ensemble/EnsembleWeighting.kt`, `data/repository/AlphaPulseRepository.kt` | `domain/ensemble/AdaptiveEnsembleLearningBddSpec.kt` |
| 21 | Prediction Audit Ledger | Done | `ui/components/PredictionLedgerSection.kt`, `data/db/Daos.kt` | `data/db/…` DAO tests |
| 22 | Per-Horizon Readiness | Done | `data/repository/AlphaPulseRepository.kt`, `ui/viewmodel/AlphaPulseViewModel.kt` | `ui/…` readiness tests |
| 23 | Forecast Scheduling | Done | `data/repository/AlphaPulseRepository.kt`, `domain/ml/TsfmLayer.kt` | `domain/ml/TsfmLayerTest.kt` |
| 24 | Persistent Background Engine | Done | `service/AlphaPulseService.kt`, `domain/ml/ModelRuntime.kt` | `model/…` service tests |
| 25 | WorkManager | Done | `data/model/WorkManagerScheduler.kt` | `model/WorkManagerSchedulerTest.kt` |
| 26 | Android Hardware Acceleration | Done | `profiling/HardwareGatekeeper.kt`, `domain/ml/ModelRuntime.kt` | `profiling/HardwareGatekeeperBddSpec.kt` |
| 27 | CPU/GPU/NPU Work Allocation | Done | `domain/ml/ModelRuntime.kt`, `domain/ml/UqLobEngine.kt` | `domain/ml/UqLobEngineTest.kt` |
| 28 | Edge SLM | Done | `domain/narrative/EdgeSlmNarrativeLayer.kt` | `domain/narrative/NarratorConstraintValidatorBddSpec.kt` |
| 29 | Quantitative Decision Router | Done | `domain/decision/DecisionRouter.kt`, `Decision1ApiRouter.kt`, `DecisionModelCatalog.kt`, `Decision1Configuration.kt` (typed config errors), `ui/components/Decision1StatusBanner.kt` (renders them on the main screen) | `domain/decision/DecisionRouterTest.kt`, `Decision1ApiRouterBddSpec.kt`, `Decision1ConfigurationBddSpec.kt`, `ui/components/ModelDockRemoteOnlyBddSpec.kt`, `ui/components/Decision1StatusBannerBddSpec.kt` |
| 30 | Needle / Agent Orchestration | Partial | `domain/agent/Agent.kt`, `domain/decision/DecisionModelCatalog.kt`, `domain/decision/Needle3Engine.kt`, `profiling/HardwareGatekeeper.kt` | `domain/agent/AgentTest.kt`, `DecisionModelCatalogBddSpec.kt`, `Needle3EngineBddSpec.kt` |
| 31 | Model Ensemble Health | Done | `domain/ensemble/EnsembleWeighting.kt`, `domain/ml/ModelRuntime.kt` | `domain/ensemble/EnsembleWeightingTest.kt` |
| 32 | Model Disagreement Engine | Done | `domain/ensemble/ModelDisagreementDetector.kt` | `domain/ensemble/EnsembleWeightingTest.kt` |
| 33 | Forecast Confidence Score | Done | `domain/decision/DecisionRouter.kt` | `domain/decision/DecisionRouterTest.kt` |
| 34 | Prediction State Machine | Done | `domain/quant/PredictionStateMachine.kt` (ISSUED → SETTLED → LEARNED / EXPIRED / VOID, illegal moves rejected), wired into the settlement path in `AlphaPulseRepository` | `domain/quant/PredictionStateMachineBddSpec.kt` |
| 35 | Data Quality Engine | Done | `domain/quality/DataQualityEngine.kt` (one score + typed issues), consumed by the `NotificationVeto` report overload | `domain/quality/DataQualityEngineBddSpec.kt`, `service/NotificationVetoQualityBddSpec.kt` |
| 36 | Offline Mode | Done | `data/model/OfflineModeManager.kt` | `model/OfflineModeManagerTest.kt` |
| 37 | Room Database | Done | `data/db/AppDatabase.kt`, `data/db/Entities.kt`, `data/db/Daos.kt` | `data/db/…` DAO tests, `androidTest/UserJourneyBddTest.kt` |
| 38 | Export System (CSV) | Done | `data/repository/AlphaPulseRepository.kt` (`exportAuditLedgerCsv`, `generate4TabUniverseCsv`) | Repository export tests |
| 39 | User Interface | Done | `ui/AlphaPulseScreen.kt`, `MainActivity.kt` | `androidTest/UserJourneyBddTest.kt` |
| 40 | Primary Dashboard | Done | `ui/components/TopWorkspaceCards.kt`, `ui/components/HeaderBar.kt` | `ui/components/ModelDockUiTest.kt` |
| 41 | Institutional Explanation Panel | Done | `ui/components/AccordionsSection.kt`, `domain/quant/QuantNarrativeAgent.kt` | `domain/…` narrative tests |
| 42 | Security Requirements | Done | `data/security/KeystoreCredentialStore.kt` | `security/…` tests |
| 43 | API Key Architecture | Done | `data/security/KeystoreCredentialStore.kt`, `domain/decision/Decision1ApiRouter.kt` | `KeystoreCredentialStore` tests |
| 44 | Failure Handling | Done | `domain/decision/Decision1ApiRouter.kt`, `data/network/ResilientBinanceClient.kt` | `domain/decision/Decision1ApiRouterBddSpec.kt` |
| 45 | Performance Requirements | Done | `domain/decision/Decision1ApiRouter.kt` (timeouts, budgets) | `domain/decision/Decision1ApiRouterBddSpec.kt` |
| 46 | Battery Requirements | Done | `data/model/BatteryModeManager.kt`, `data/model/WorkManagerScheduler.kt` | `model/BatteryModeManagerTest.kt` |
| 47 | Model Download Architecture | Done | `data/model/ModelArtifactSource.kt`, `data/model/ModelArtifactInstaller.kt`, `data/model/ModelDownloadStore.kt` | `data/model/ModelArtifactSourceContractTest.kt`, `profiling/HardwareGatekeeperBddSpec.kt` |
| 48 | Model Selection Benchmark | Gap | Roadmap text only (`release/ReleaseStrategy.kt`); no on-device benchmark harness | — |
| 49 | Critical Model Selection Principle | Done | `data/model/ModelManager.kt`, `profiling/HardwareGatekeeper.kt` | `profiling/HardwareGatekeeperBddSpec.kt` |
| 50 | Walk-Forward Validation | Gap | Learning rate documented as walk-forward calibrated (`domain/ensemble/EnsembleWeighting.kt`); no walk-forward backtest driver | — |
| 51 | No Data Leakage Requirement | Gap | No explicit guard test asserting future data is never fed to a forecast | — |
| 52 | Testing Requirements | Done | Test suites under `app/src/test` and `app/src/androidTest` | `testing/TestingRequirements.kt`, `jacocoCoverageGate` |
| 53 | Android Lifecycle Testing | Done | Lifecycle behaviour in `service/AlphaPulseService.kt` | `model/AndroidLifecycleTest.kt` |
| 54 | Session Recovery | Done | `data/model/ModelManager.kt`, `data/db/Entities.kt` (`SessionEntity`) | `model/…` session tests |
| 55 | Model Versioning | Done | `data/model/VersionInfo.kt`, `data/db/Entities.kt` (`versionTag`) | `data/model/…` tests |
| 56 | Observability | Done | `ui/components/ObservabilityDashboard.kt` | `ui/…` observability tests |
| 57 | Notifications | Done | `service/AlphaPulseService.kt` | `model/…` service tests |
| 58 | Notification Veto | Done | `service/NotificationVeto.kt` | `service/NotificationVetoBddSpec.kt` |
| 59 | AI Narrator Requirements | Done | `domain/narrative/EdgeSlmNarrativeLayer.kt` | `domain/narrative/NarratorConstraintValidatorBddSpec.kt` |
| 60 | Research Mode | Done | `ui/viewmodel/AlphaPulseViewModel.kt` | `model/ResearchModeTest.kt` |
| 61 | Recommended Final Architecture | Done | `di/ServiceLocator.kt` | `ServiceLocatorRobolectricTest.kt` |
| 62 | Release Strategy | Done | `release/ReleaseStrategy.kt` | `release/ReleaseProgressBddSpec.kt` |
| 63 | Definition of Done | Done | `release/DefinitionOfDone.kt` | `androidTest/UserJourneyBddTest.kt` |
| 64 | Most Important Architectural Change | Done | `release/ArchitecturalChange.kt` | `release/ReleaseProgressBddSpec.kt` |

## Open gaps (reported, not hidden)

Four sections are **not** fully implemented. They are listed here so the next
piece of work starts from a known place rather than rediscovering them:

1. **§30 Needle / Agent Orchestration (engine only)** — everything except
   execution is in place: the `needle3.cact` weights are registered with their real
   Hugging Face coordinate, size and SHA-256, the download is gated to mid-tier
   devices (3.5 GB RAM, 4 CPU cores), and the run path exists. What is missing is
   the native Cactus engine itself: this build has no NDK directory, no
   `externalNativeBuild`, and no vendored `android-arm64/libneedle.a`/`needle.h`.
   `Needle3Engine` therefore **fails closed** with a typed `NotBundled` state
   instead of fabricating narrative text (§59). Concrete reason: the static engine
   could not be fetched and verified in this environment, and shipping an
   unverified native library would be worse than shipping none.
2. **§48 Model Selection Benchmark** — the benchmark exists only as roadmap text.
   A harness that times each mounted model and records tokens/second and p95
   latency is missing.
3. **§50 Walk-Forward Validation** — the ensemble learns online and its learning
   rate is *documented* as walk-forward calibrated, but no walk-forward driver
   replays history in train/test folds.
4. **§51 No Data Leakage Requirement** — nothing prevents a future candle from
   reaching a forecast. A guard test is needed before any backtest can be trusted.

§10 and §35 were previously partial and are now complete: the trade tape has an
analyzer reading the stored buffer, and data quality has one engine whose report
the notification veto now consumes directly.

Decision-1 no longer has a UI gap: `Decision1StatusBanner` renders the typed
configuration error directly under the model dock on the main screen, shows
nothing once the router is configured, and adds no downloadable entry (§29).
