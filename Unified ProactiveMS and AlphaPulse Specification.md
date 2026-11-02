# ProactiveMS + AlphaPulse (V14.4) Unified Specification
**Native Institutional Quantitative Cryptocurrency Prediction Workstation & Cancellation-First Market Structure Automation (Production-Grade Mobile Edition)**

* **Product**: ProactiveMS + AlphaPulse Android Unified Workstation
* **Baseline Specifications Merged**:
  * `Proactive Market Structure Automation Spec (Production Grade Mobile Edition)` (`SPECIFICATION.md` Parts I–VIII, Sections 1–38, Appendices A & B)
  * `AlphaPulse Android: Native Institutional Quantitative Cryptocurrency Prediction Workstation` (`AlphaPulse_Android_SPEC_V14-3.md` Sections 1–64, Appendix A)
  * `SPEC_TRACEABILITY.md` ($\S 1$–$\S 72$) & `FINALIZED_CONTRACTS.md` (`C-01`–`C-22`)
* **Target Platform**: Native Android (`arm64-v8a` strictly enforced per Contract `C-03`, with offline-native architectural parity documented for iOS in Appendix C)
* **Forecast Horizons**: `1m`, `5m`, `15m`, `30m`, `1h` (plus `4h` and `1d` strategic structure)
* **Primary Market**: Crypto perpetual futures & spot validation
* **Initial Exchange**: Binance USDⓈ-M Futures + Binance Spot + CoinGlass Public Futures API
* **Secondary Exchanges**: Bybit, Hyperliquid
* **Architecture**: Kotlin + Jetpack Compose + Coroutines/Flow + Room SQLite (v7) + OkHttp/WebSockets + ONNX Runtime Mobile / ExecuTorch / TFLite + Vendored Cactus Needle 3 (`libneedle.a` + `needle3.cact`)

---

## Table of Contents

* **Part I: Unified System Overview, Vision & Mobile Hardware/Security Guardrails**
  * 1. Product Vision, System Purpose & Cancellation-First Philosophy ($\text{ProactiveMS } \S 1 + \text{AlphaPulse } \S 1$)
  * 2. Core Design Principle & End-to-End Unified Topology ($\text{AlphaPulse } \S 2, \S 61, \S 64$)
  * 3. Zero-Cloud Mobile Architecture, Hardware Acceleration & Workload Allocation ($\text{ProactiveMS } \S 2 + \text{AlphaPulse } \S 26, \S 27, \S 45, \S 46$)
  * 4. Persistent Foreground Engine, API Key Security, Trade Sessions & Orphaned Orders ($\text{ProactiveMS } \S 3 + \text{AlphaPulse } \S 24, \S 25, \S 42, \S 43$)
* **Part II: Data Architecture, 30-Day Needle 3 Training & Institutional Universe Screening**
  * 5. Native Networking Layer, Multi-Exchange Failover & Mandatory Feeds ($\text{ProactiveMS } \S 4 + \text{AlphaPulse } \S 5, \S 6$)
  * 6. Data Throttling, Stale Data Expiration, Exact Scheduling & Data Quality Engine ($\text{ProactiveMS } \S 5 + \text{AlphaPulse } \S 23, \S 35, \S 36$)
  * 7. Unified Institutional Universe Scanner & Risk-Adjusted Ranking ($\text{AlphaPulse } \S 3, \S 4 \text{ replacing ProactiveMS } \S 6.1$)
  * 8. Needle 3: 30-Day Multi-Horizon Binance Ingestion & Tri/Quad/Penta Ensemble Calibration ($\text{AlphaPulse } \S 20, \S 30 + \text{Contract C-12, C-18}$)
* **Part III: Deep Microstructure, Multi-Model Forecasting & Market Structure Modeling**
  * 9. Full Order Book Reconstruction, Horizon-Specific Pain Paths, Liquidation Heatmap & Trade Tape ($\text{AlphaPulse } \S 7, \S 8, \S 9, \S 10$)
  * 10. Tri/Quad/Penta-Factor Engine, Full Volume Profile, Neural UQ-LOB & AGA-HMM ($\text{AlphaPulse } \S 11, \S 12, \S 13, \S 14, \S 15$)
  * 11. Time-Series Foundation Model (TSFM) Strategy, Covariate Tensor & Probabilistic Cones ($\text{AlphaPulse } \S 16, \S 17, \S 18, \S 19$)
  * 12. Model Ensemble Health, Disagreement Engine, Confidence Score & Prediction State Machine ($\text{AlphaPulse } \S 31, \S 32, \S 33, \S 34$)
  * 13. Core Market-Structure Objects, Level Detection, Path Modeling & Coin Calibration ($\text{ProactiveMS } \S 7, \S 8, \S 9, \S 10, \S 11$)
* **Part IV: The Campaign Library, Prediction-Driven Staging & Readiness Gating**
  * 14. Complete Campaign Taxonomy & Core Campaign Templates ($\text{ProactiveMS } \S 12, \S 13$)
  * 15. Tactical vs. Strategic Bias Separation, Bias Score Model & Asymmetric Board Templates ($\text{ProactiveMS } \S 14, \S 15, \S 16, \S 17$)
  * 16. Prediction-Driven Campaign Generation Algorithm, Readiness Gate ($>90\%$ Cone Hit Rate) & State Machine ($\text{ProactiveMS } \S 18, \S 19 + \text{AlphaPulse } \S 22$)
  * 17. Position Sizing, Entry Ladder Construction, Proactive Order Architecture, Conflict Resolution & Manual Overrides ($\text{ProactiveMS } \S 20, \S 21, \S 22, \S 23, \S 24$)
* **Part V: Monitoring, ProactiveMS Walk-Forward Risk & Cancellation-First Invalidation**
  * 18. Live Monitoring Loop, Asymmetric Board Survival & Microstructural Invalidation Model ($\text{ProactiveMS } \S 25, \S 26, \S 27, \S 28, \S 29$)
  * 19. ProactiveMS Walk-Forward Risk & Cancellation-First Invalidation Engine (`0.50R` Default Candidate / `2.00R` Hard Backstop)
* **Part VI: On-Device AI Orchestration, Unified 7-Tab UI, Persistence & Operations**
  * 20. Edge SLM Narrator, Quantitative Decision Router & Needle 3 Agent DAG ($\text{AlphaPulse } \S 28, \S 29, \S 30, \S 41, \S 59$)
  * 21. Room Database Schema, 25-Field Unified Audit Ledger & Multi-Format Export System ($\text{ProactiveMS } \S 30, \S 31 + \text{AlphaPulse } \S 21, \S 37, \S 38$)
  * 22. Unified 7-Tab Navigation UI (`ProactiveMS` + `AlphaPulse` Workstation), Notifications & Observability ($\text{ProactiveMS } \S 32 + \text{AlphaPulse } \S 39, \S 40, \S 56, \S 57, \S 58, \S 60$)
  * 23. Operational Guardrails, Failure Handling, Configuration & Engineering Build Checklists ($\text{ProactiveMS } \S 33, \S 34, \S 35 + \text{AlphaPulse } \S 44$)
* **Part VII: Model Management, Walk-Forward Validation, Testing & Production Deployment**
  * 24. Model Download Architecture, Benchmarking, Selection Principles & Versioning ($\text{AlphaPulse } \S 47, \S 48, \S 49, \S 55$)
  * 25. Walk-Forward Validation, No-Data-Leakage Guard, TDD, Lifecycle Testing & Session Recovery ($\text{AlphaPulse } \S 50, \S 51, \S 52, \S 53, \S 54$)
  * 26. Production Execution Modes, Backtesting Simulation, Release Strategy & Definition of Done ($\text{ProactiveMS } \S 36, \S 37, \S 38 + \text{AlphaPulse } \S 62, \S 63$)
* **Appendices**
  * Appendix A: On-Device AI/ML Model Catalogue and Sources ($\text{AlphaPulse Appendix A}$)
  * Appendix B: Default Field List for a Unified Campaign & Audit Object ($\text{ProactiveMS Appendix A Extended}$)
  * Appendix C: Separated Offline-Native Technology Stacks ($\text{ProactiveMS Appendix B}$)

---

## Part I: Unified System Overview, Vision & Mobile Hardware/Security Guardrails

### 1. Product Vision, System Purpose & Cancellation-First Philosophy

#### 1.1 AlphaPulse Quantitative Workstation Vision
AlphaPulse Android transforms the validated HTML5 V14.3 / V14.4 quantitative engine into a persistent, self-calibrating, multi-model quantitative workstation capable of operating continuously while the device is locked or the application is backgrounded. It provides an unbroken institutional pipeline:

$$\text{Institutional Screening} \rightarrow \text{Deep Market Ingestion} \rightarrow \text{Microstructure Analysis} \rightarrow \text{Deterministic Quant Models} \rightarrow \text{Neural/TSFM Forecasting} \rightarrow \text{Ensemble Fusion} \rightarrow \text{Online Learning} \rightarrow \text{Probabilistic Confidence} \rightarrow \text{Quantitative Audit} \rightarrow \text{On-Device Explanation}$$

The application never delegates numerical price prediction to a conventional conversational LLM. Instead:
* Deterministic quantitative models produce measurable kinematic, liquidity-pain, and volume-confluence signals;
* Time-series foundation models (TSFMs) provide independent multi-step probabilistic forecasts;
* Neural microstructure models (`UQ-LOB`, `AGA-HMM`) analyze order-book depth and regime transitions;
* A Tri/Quad/Penta-Factor ensemble engine determines forecast convergence and confidence envelopes;
* Online learning (AdamW / Kalman / Online Mirror Descent / SGD) continuously recalibrates per-asset, per-horizon ensemble weights;
* An on-device grammar-constrained router (`Needle3DecisionRouter`) and optional Edge SLM explain the resulting numerical state.

#### 1.2 ProactiveMS Cancellation-First Trading Automation Philosophy
Instead of waiting for classic confirmation and then reacting late, ProactiveMS pre-stages trade campaigns in advance using AlphaPulse's multi-horizon forecasts, confidence cones, and structural level maps, then cancels or exits aggressively when model-degradation or microstructural invalidation evidence appears.

| Purpose of this Specification | Translate the combined AlphaPulse + ProactiveMS method into a machine-executable mobile system. | Define screening, 30-day calibration, forecasting, order staging, walk-forward invalidation, and safety controls. | Make the output concrete enough for engineering, TDD/BDD verification, walk-forward simulation, and live deployment. |
| :--- | :--- | :--- | :--- |

**Core Trading Principles:**
* Markets often move to the nearest efficient liquidity pain point before revealing whether the larger reversal or continuation is real.
* The best trade is often available before classic confirmation, but only if initial size is lighter, readiness and cone accuracy ($>90\%$) are verified, and cancellation rules are strict.
* A campaign is a staged hypothesis built on AlphaPulse's multi-horizon probability distribution, not a single blind order.
* Each campaign has a structural level map, a prediction-anchored entry ladder, invalidation rules (statistically significant cone breach, SGD re-ranking shift, and microstructural evidence), monitoring rules, and a take-profit ladder.
* The system must know when to do nothing. Avoiding bad participation when readiness or cone hit rate drops below threshold is part of the edge.

| Trading Principle | Pre-stage around AlphaPulse's highest-probability pain path. | Monitor cone integrity, SGD rank, and order-flow evidence in real time. | Cancel aggressively within walk-forward loss tolerance (`0.50R` default) before the `2.00R` hard stop is ever reached. |
| :--- | :--- | :--- | :--- |

---

### 2. Core Design Principle & End-to-End Unified Topology

The HTML5 Tri-Factor model remains intact as the baseline and extends into a full Penta-Factor forecasting and cancellation-first execution architecture:

```text
                    PROACTIVEMS + ALPHAPULSE ANDROID WORKSTATION
                                         │
                  Institutional Screener (FR-001 + FR-002 + FR-003)
           [4-Filter Gate + 1Y Calmar > 0 & Sharpe > 0 + Spread < 0.10%]
                                         │
                ┌────────────────────────┴────────────────────────┐
                ▼                                                 ▼
       Market Data Layer                                  Historical Room DB
   (Binance / Bybit / Hyperliquid                 (Needle 3 30-Day Multi-Horizon
      + CoinGlass Validation)                       Binance Public Data Calibration)
                │                                                 │
                └────────────────────────┬────────────────────────┘
                                         ▼
                               Microstructure Engine
                ┌───────────────┬────────┴────────┬───────────────┐
                ▼               ▼                 ▼               ▼
           W1: Kinematic   W2: Pain Path    W3: Confluence   Neural UQ-LOB
              (M1_t)         (M2_t LOB)      (FRVP / VWAP)    & AGA-HMM
                └───────────────┴────────┬────────┴───────────────┘
                                         ▼
                             Independent TSFM Layer
                        ┌────────────────┴────────────────┐
                        ▼                                 ▼
               W4: Chronos-Bolt                   W5: TimesFM 2.0 / Kronos
              (M4_t Quantile Path)                 (M5_t Structural Path)
                        └────────────────┬────────────────┘
                                         ▼
                    Penta-Factor Ensemble Fusion (Sum W = 1.000)
                                         │
                    Online Learning Layer (Per-Coin, Per-Horizon)
                        ┌────────────────┴────────────────┐
                        ▼                                 ▼
              Prediction Distribution              Confidence Score
            (P10-P90, +-95% Cone, +-1 Sigma)     & Disagreement Check
                        └────────────────┬────────────────┘
                                         ▼
              Readiness & Quality Gate (LTF/MTF/HTF + 5/5 + 2/2 Dock
                    + >90.0% Per-Horizon Settled Cone Hit Rate)
                                         │
                        ┌────────────────┴────────────────┐
                        ▼                                 ▼
             Needle 3 Decision Router               Edge SLM Narrator
                        └────────────────┬────────────────┘
                                         ▼
             ProactiveMS Campaign Engine (6 Families, Boards A/B/C/D)
                                         │
             Walk-Forward Cancellation-First Invalidation (0.50R Default)
                          + Absolute Hard Stop (2.00R Max)
                                         │
                25-Field Prediction & Execution Audit Ledger (Room v7)
                                         │
             7-Tab Compose UI (Screener | AlphaPulse | Dashboard | Telemetry
                               | Guardrails | Replay | Audit)
```

---

### 3. Zero-Cloud Mobile Architecture, Hardware Acceleration & Workload Allocation

To ensure user privacy, deterministic low latency, and zero recurring cloud infrastructure costs, the entire quantitative and execution engine operates $100\%$ locally on client mobile hardware.

#### 3.1 Android Hardware Acceleration (`HardwareGatekeeper.kt`, `ModelRuntime.kt`)
The ML subsystem provides an explicit hardware abstraction:
```text
InferenceBackend
 ├── CPU
 ├── GPU
 ├── NPU/DSP
 └── Automatic
```
Candidate runtimes include ONNX Runtime Mobile, ExecuTorch, MediaPipe ML inference, Android NNAPI, and Vulkan GPU execution. The application benchmarks available backends at installation/runtime and selects the fastest validated backend per model:
* **CPU**: Native HTTP/2 and WebSocket networking, Room SQLite database operations, deterministic quantitative calculations (`QuantMath.kt`), NTP clock scheduling, and ensemble optimization mathematics.
* **GPU**: Spatial L2/L3 tensor CNN inference (`UqLobEngine.kt`), parallel numerical processing, and selected neural workloads.
* **NPU / DSP**: Compatible quantized neural inference, Edge SLM (`EdgeSlmNarrativeLayer.kt`), and validated TSFM checkpoints. Every model undergoes an explicit backend capability test before NPU assignment.

#### 3.2 Performance Requirements
| Component | Target Requirement |
| :--- | :--- |
| Universe screening | $< 5\text{ sec}$ target (subject to external exchange rate limits) |
| L2 order-book processing | $\le 250\text{ ms}$ where exchange permits |
| Forecast evaluation cycle | $\le 10\text{ sec}$ |
| On-device decision routing (`Needle3DecisionRouter`) | $< 100\text{ ms}$ target (single on-device turn, zero network calls) |
| Lightweight neural inference | $< 100\text{ ms}$ target |
| UI frame rate | $60\text{ FPS}$ minimum |
| Database writes | Non-blocking asynchronous Coroutines/Flow |
| Prediction validation | Exact scheduled candle/horizon boundary |
| WebSocket & app restart recovery | Automatic |

#### 3.3 Battery Modes & Pre-Flight Hardware Guardrails
The system distinguishes four user-selectable power modes managed by `BatteryModeManager.kt`:
1. **Live Quant Mode**: Maximum real-time WebSocket ingestion, L2 reconstruction, inference, and active campaign monitoring.
2. **Balanced Mode**: Reduced order-book sampling and inference frequency for background screening.
3. **Analysis Mode**: No continuous WebSockets; on-demand historical and ledger inspection.
4. **Offline Mode**: Zero network usage; historical backtesting, stored ledger audit, and local SLM narration.

**Pre-Flight Hardware & C
