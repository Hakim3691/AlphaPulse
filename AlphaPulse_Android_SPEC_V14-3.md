AlphaPulse Android

Native Institutional Quantitative Cryptocurrency Prediction Workstation

Product: AlphaPulse Android
Baseline: AlphaPulse PRO QUANT V14.3 TRI-FACTOR HTML5 SPA
Target: Native Android
Primary function: Institutional cryptocurrency futures screening, multi-horizon probabilistic forecasting, adaptive ensemble learning and on-device quantitative intelligence
Forecast horizons: 1m, 5m, 15m, 30m, 1h
Primary market: Crypto perpetual futures
Initial exchange: Binance USDⓈ-M Futures
Secondary exchanges: Bybit, Hyperliquid
Architecture: Kotlin + Jetpack Compose + Coroutines + Room + WebSockets + native ML inference


---

1. Product Vision

AlphaPulse Android shall transform the existing HTML5 SPA into a persistent, self-calibrating, multi-model quantitative workstation capable of operating continuously while the device is locked or the application is backgrounded.

The Android application shall preserve the validated functionality of the HTML5 V14.3 implementation while eliminating browser-specific compromises.

The native application shall therefore provide:

Institutional screening → deep market ingestion → microstructure analysis → deterministic quantitative models → neural/time-series forecasting → ensemble fusion → online learning → probabilistic confidence → quantitative audit → on-device explanation

The application shall not delegate price prediction to a conventional conversational LLM.

Instead:

- deterministic quantitative models produce measurable signals;
- time-series foundation models provide independent forecasts;
- neural microstructure models analyse order-book structure;
- an ensemble engine determines forecast convergence;
- online learning continuously recalibrates the ensemble;
- an optional Edge SLM explains the resulting numerical state.


---

2. Core Design Principle

The HTML5 Tri-Factor model shall remain intact as the baseline.

The Android architecture shall extend it to:

ALPHAPULSE ANDROID
                           │
                 Institutional Screener
                           │
          ┌────────────────┴────────────────┐
          │                                 │
     Market Data Layer                 Historical DB
          │                                 │
   ┌──────┼─────────┐                 Room SQLite
   │      │         │
 Binance Bybit Hyperliquid
   │      │         │
   └──────┼─────────┘
          │
   Microstructure Engine
          │
 ┌────────┼─────────┬─────────────┐
 │        │         │             │
Kinematic Pain Path Confluence   Neural LOB
 │        │         │             │
 └────────┴─────────┴─────────────┘
                   │
             TSFM Forecast
                   │
        ┌──────────┴──────────┐
        │                     │
  Existing Tri-Factor     TSFM / Neural
        │                     │
        └──────────┬──────────┘
                   │
             Ensemble Fusion
                   │
          Online Learning Layer
                   │
        ┌──────────┴──────────┐
        │                     │
 Prediction Distribution   Confidence
        │                     │
        └──────────┬──────────┘
                   │
          Decision / Risk Gate
                   │
          Edge SLM Narrator
                   │
              Android UI


---

3. Functional Requirements

FR-001: Institutional Universe Scanner

The Android application shall scan the Binance USDⓈ-M Futures universe automatically.

The scanner shall retain the existing three-stage pipeline.

Stage 1: Institutional 4-Filter Gate

A contract shall qualify when all four conditions are satisfied either:

1. today, or
2. simultaneously on at least one day during the previous 10 days.

The four conditions remain:

Filter 1: Institutional Volume

24h Quote Volume >= $5,000,000 USDT

Filter 2: Aggressive Taker Buying

Taker Buy Ratio >= 50%

Filter 3: Volume/OI Velocity

24h Quote Volume / Open Interest > 1.0

Filter 4: Conviction Displacement

|Close - Open| / (High - Low) >= 40%


---

4. Risk-Adjusted Universe Ranking

FR-002: Calmar Filter

The application shall calculate:

- total return;
- annualized return;
- maximum drawdown;
- Calmar ratio.

Only:

Calmar > 0

shall proceed.

FR-003: Sharpe Ranking

The application shall calculate:

Sharpe = (Annualized Return - Rf) / Annualized Volatility

The HTML5 baseline uses:

Rf = 4%

The Android implementation shall support a dynamic risk-free/opportunity-cost source, subject to data availability.

The original 4% value shall remain available as a fallback.


---

5. Native Networking Layer

FR-004: Exchange Connectivity

The application shall use native HTTP/WebSocket infrastructure rather than browser APIs.

Recommended stack:

- Kotlin;
- OkHttp;
- Retrofit where appropriate;
- Kotlin Coroutines;
- Flow/StateFlow;
- HTTP/2 connection pooling;
- WebSocket reconnection manager.

FR-005: Endpoint Resilience

The system shall support endpoint failover.

The baseline Binance endpoints shall be retained, with exchange-specific adapters introduced for:

- Binance;
- Bybit;
- Hyperliquid.

The exchange layer shall use an abstraction such as:

ExchangeDataProvider
 ├── BinanceProvider
 ├── BybitProvider
 └── HyperliquidProvider

This prevents the quantitative engine from becoming exchange-specific.


---

6. Real-Time Market Data Engine

The Android application shall continuously ingest:

Price

- trades;
- aggregate trades;
- candles;
- mark price;
- index price.

Derivatives

- open interest;
- OI change;
- funding rate;
- basis;
- taker buy/sell volume;
- liquidation events.

Order Book

- bid/ask depth;
- depth deltas;
- spread;
- imbalance;
- depth velocity;
- depth replenishment;
- depth depletion;
- liquidity voids.

Liquidations

- long liquidations;
- short liquidations;
- liquidation clusters;
- liquidation intensity;
- liquidation direction.


---

7. Full Order Book Reconstruction

FR-006: Deep L2/L3 Architecture

The Android application shall replace the SPA's limited Top-20/Top-50 snapshots with continuously reconstructed order books.

The system shall support:

Top 20
Top 50
Top 100
Top 500
Top 1000

where supported by the exchange.

The system shall maintain:

OrderBookSnapshot
OrderBookDelta
OrderBookState
LiquidityShelf
LiquidityVoid

The order-book engine shall detect:

- absorption;
- spoofing-like behaviour;
- bid stacking;
- ask stacking;
- rapid cancellation;
- replenishment;
- liquidity migration;
- depth imbalance;
- spread expansion;
- depth collapse.


---

8. Horizon-Specific Liquidity Pain Model

The SPA currently uses:

μp(t) = μp × exp(-λ(t-1))

as a proxy for higher-timeframe liquidity behaviour.

The Android application shall remove this limitation.

It shall independently calculate:

μp,1m
μp,5m
μp,15m
μp,30m
μp,60m

Each vector shall use liquidity actually reachable within the expected volatility range of that horizon.

For example:

1m  → immediate L2 liquidity
5m  → near-book liquidity
15m → intraday liquidity shelves
30m → broader liquidation bands
1h  → macro liquidity / session extremes

This shall become a Horizon-Aware Pain Path Engine.


---

9. Proprietary Liquidation Heatmap

FR-007: Multi-Exchange Liquidation Engine

The Android application shall support:

- authenticated liquidation APIs where available;
- Binance Force Order streams;
- Bybit liquidation streams;
- Hyperliquid liquidation data where available;
- historical liquidation persistence.

The system shall construct its own local liquidation map.

Rather than selecting only the largest cluster, it shall calculate a liquidity attraction score such as:

Attraction(P)
=
LiquidationVolume(P)
/
Distance(Spot,P)

The system shall identify:

- nearest liquidation magnet;
- dominant upper shelf;
- dominant lower shelf;
- cumulative upper liquidation volume;
- cumulative lower liquidation volume;
- liquidity asymmetry;
- horizon-specific liquidation targets.


---

10. Continuous Institutional Trade-Tape Analysis

FR-008: Rolling Trade Buffer

The 500-trade REST snapshot shall be eliminated.

The application shall maintain a rolling trade buffer in Room.

Minimum analytical windows:

1 minute
5 minutes
15 minutes
30 minutes
1 hour
24 hours

The engine shall calculate:

- trade-size distribution;
- 95th percentile trade size;
- 99th percentile trade size;
- taker imbalance;
- net aggressive volume;
- burst intensity;
- directional block flow.

This shall produce:

InstitutionalShock(1m)
InstitutionalShock(5m)
InstitutionalShock(15m)
InstitutionalShock(30m)
InstitutionalShock(1h)


---

11. Existing Tri-Factor Engine

The Android application shall retain the existing three-factor model.

Vector 1: Kinematic

The following shall remain:

- LTF zeroed kinematic component;
- Markov-GARCH regime detection;
- adaptive kurtosis threshold;
- momentum burst state;
- mean-reversion state;
- temporal attention for the 1h horizon.

Vector 2: Liquidity Pain

The existing mathematical model shall remain the baseline, but Android shall replace its proxies with:

- full order-book reconstruction;
- liquidation shelves;
- multi-exchange liquidity;
- horizon-specific liquidity paths;
- continuous institutional trade tape.

Vector 3: Confluence Alpha

The Android version shall retain:

- FRVP;
- VWAP;
- POC;
- UQ-LOB;
- regime context.

However, the simplified SPA implementations shall be upgraded where practical.


---

12. Full Volume Profile

The Android application shall construct a genuine rolling 24-hour volume profile.

It shall calculate:

- POC;
- VAH;
- VAL;
- 70% value area;
- LVNs;
- HVNs;
- volume distribution;
- VWAP;
- anchored VWAP;
- value-area rotation.

The fixed 50-bin implementation shall become adaptive.

The application shall support Freedman-Diaconis-derived bin widths:

Bin Width = 2 × IQR × n^(-1/3)

while retaining configurable minimum and maximum resolution limits.


---

13. Neural UQ-LOB

The Android application shall provide a production-capable neural order-book uncertainty model.

Inputs shall include spatial L2/L3 tensors representing:

price distance
bid volume
ask volume
bid/ask imbalance
depth velocity
order cancellation
order replenishment
spread
trade flow

The model shall output at minimum:

directional probability
uncertainty score
epistemic uncertainty
aleatoric uncertainty
confidence tier

The model shall be deployable through:

- ONNX Runtime Mobile;
- ExecuTorch;
- Android-compatible hardware acceleration.


---

14. AGA-HMM Upgrade

The SPA's kurtosis-based approximation shall remain available as a deterministic fallback.

Android shall additionally support the neural implementation described in the source specification.

The engine shall expose:

Regime
Regime Probability
Transition Probability
Regime Confidence

with states including at least:

Momentum Burst
Mean Reversion
Transition
Uncertain


---

15. New Fourth Forecasting Factor

This is the most important architectural addition I recommend.

The existing system should not become a conventional LLM predictor.

Instead, AlphaPulse should become:

Tri-Factor Quantitative Ensemble
                +
Time-Series Foundation Model
                =
Four-Factor Forecasting Engine

The fourth vector shall be:

μTSFM

and its weight:

WTSFM

The resulting forecast becomes:

μ̂ =
WK μK
+
WP μP
+
Wα μα
+
WTSFM μTSFM
+
Δμbias

with:

WK + WP + Wα + WTSFM = 1


---

16. Recommended Time-Series Model Strategy

The Android application shall implement a pluggable TSFM interface, rather than hard-coding one model.

TimeSeriesForecastProvider
        │
        ├── KronosProvider
        ├── FinTextChronosProvider
        ├── ChronosProvider
        ├── TimesFMProvider
        └── FutureModelProvider

Priority 1: Finance/K-line-specific model

Kronos Small should be evaluated first because its design is specifically oriented toward financial K-line forecasting.

Priority 2: Finance-pretrained Chronos

FinText provides finance-pretrained Chronos models, including a 46.2M-parameter Chronos Small checkpoint. 

This is particularly attractive for an Android implementation because the model is much smaller than general-purpose LLMs.

Priority 3: Generic Chronos Small

Amazon's Chronos-T5 Small is 46M parameters and produces probabilistic forecasts through sampled future trajectories. 

The Chronos family also has 8M and 20M variants, allowing an explicit mobile-performance ladder. 

Priority 4: TimesFM

TimesFM should remain in the architecture as an independent benchmark/model provider rather than being treated as the only TSFM.

The current TimesFM project has evolved considerably, with TimesFM 2.5 at 200M parameters and TimesFM 3.0 now available, although the current 3.0 pretrained weights have licensing restrictions that need to be considered for commercial/production deployment. 

Therefore the Android PRD shall require model licensing and mobile-runtime validation before selecting the production checkpoint.


---

17. TSFM Input Representation

The TSFM layer shall not be limited to close price.

The application shall support a structured market-state tensor containing:

Timestamp
Open
High
Low
Close
Volume
Log Return
Realized Volatility
Funding
Open Interest
OI Change
Taker Buy Ratio
VWAP Distance
POC Distance
VAH Distance
VAL Distance
L2 Imbalance
Spread
Depth Velocity
Liquidation Intensity
Upper Liquidity Distance
Lower Liquidity Distance
Regime

Where a model cannot consume covariates directly, AlphaPulse shall create an appropriate model-specific representation while preserving the complete state for ensemble fusion.


---

18. Probabilistic Forecasting

The TSFM layer shall preferably return:

P10
P25
P50
P75
P90

or equivalent probabilistic outputs.

The application shall transform these into:

Expected Price
Lower Confidence Boundary
Upper Confidence Boundary
Forecast Distribution
Forecast Skew
Forecast Dispersion

This will integrate directly with AlphaPulse's existing confidence-cone architecture.


---

19. Independent Model Cross-Validation

Every TSFM forecast shall be recorded separately.

Example:

Current Price       $X

Tri-Factor          $X.XX
Kronos              $X.XX
FinText Chronos     $X.XX
TimesFM             $X.XX

Final Ensemble      $X.XX

This prevents the foundation model from becoming a hidden component.

The system must be able to answer:

> "Which model was responsible for this forecast?"




---

20. Adaptive Ensemble Learning

The existing inverse-RMSE initialization shall be retained as a baseline.

However, Android shall replace the SPA's:

10^5 Gradient Multiplier

with a numerically stable online optimizer.

The architecture shall support:

- AdamW;
- Kalman filtering;
- Online Mirror Descent;
- standardized feature gradients.

The production optimizer shall be selected through walk-forward validation.

Weights shall be persistent per asset and per horizon.

For example:

BTCUSDT
 ├── 1m weights
 ├── 5m weights
 ├── 15m weights
 ├── 30m weights
 └── 1h weights

This is substantially more powerful than the SPA's single generic weight state.


---

21. Prediction Audit Ledger

Room shall replace browser localStorage.

Every prediction shall record:

Prediction ID
Symbol
Exchange
Timestamp
Horizon
Spot Price
Kinematic Forecast
Pain Path Forecast
Alpha Forecast
TSFM Forecast
Final Forecast
Upper Bound
Lower Bound

WK
WP
Wα
WTSFM

Regime
Regime Confidence
LOB Confidence
TSFM Confidence

Realized Price
Absolute Error
Percentage Error
Cone Hit/Miss
Prediction Status
Learning Action
Optimizer Update
Model Version

No historical rows shall be automatically deleted.


---

22. Per-Horizon Readiness

The existing readiness framework shall remain.

LTF Ready

>=2 settled 1m predictions
AND
>=1 settled 5m prediction

MTF Ready

>=2 settled 15m predictions
AND
>=1 settled 30m prediction

HTF Ready

>=2 settled 30m predictions
AND
>=1 settled 1h prediction

Android shall additionally display:

TSFM Ready
Neural LOB Ready
Liquidity Heatmap Ready
Ensemble Ready

based on actual validation availability.


---

23. Forecast Scheduling

The HTML5 rule shall be preserved:

> New forecasts shall be injected approximately 20 seconds before expiry of the existing forecast for that horizon.



Android shall improve this through exact scheduling.

Forecast timestamps shall be aligned to exchange/candle boundaries and an NTP-synchronised system clock.

The system shall maintain:

Exchange Time
NTP Time
Device Time
Clock Offset

and shall never rely solely on System.currentTimeMillis().


---

24. Persistent Background Engine

FR-020: Foreground Quant Service

A persistent Android Foreground Service shall run the live engine.

It shall maintain:

- WebSockets;
- 10-second evaluation cycles;
- order-book reconstruction;
- trade ingestion;
- liquidation ingestion;
- forecast validation;
- model inference;
- database persistence.

The service shall continue operating when:

- screen is locked;
- another application is open;
- AlphaPulse UI is not visible.

Battery optimisation behaviour shall be explicitly handled.


---

25. WorkManager

WorkManager shall be used for:

- historical backfills;
- database maintenance;
- model downloads;
- model updates;
- deferred exports;
- recovery jobs;
- periodic integrity checks.

WorkManager shall not replace the real-time WebSocket service.


---

26. Android Hardware Acceleration

The ML subsystem shall provide an abstraction:

InferenceBackend
 ├── CPU
 ├── GPU
 ├── NPU/DSP
 └── Automatic

The application shall benchmark available backends at installation/runtime.

Candidate runtimes:

- ExecuTorch;
- ONNX Runtime Mobile;
- MediaPipe LLM/ML inference where applicable;
- Android NNAPI where supported;
- Vulkan GPU execution where supported.

The application shall select the fastest validated backend for each model.


---

27. CPU/GPU/NPU Work Allocation

The application shall deliberately distribute workloads.

CPU

- networking;
- database;
- deterministic quantitative calculations;
- scheduling;
- ensemble mathematics.

GPU

- CNN/L2 tensor inference;
- parallel numerical processing;
- selected neural workloads.

NPU/DSP

- compatible quantized neural inference;
- Edge SLM;
- selected TSFM/neural workloads where supported.

The application shall not assume that every model can execute on the NPU.

Each model shall undergo a backend capability test.


---

28. Edge SLM

The source document proposes:

- Llama 3.2 1B;
- Gemma 2 2B;
- Phi-3.5 Mini.

The Android requirements shall retain this concept but redefine the SLM as a Narrative/Diagnostic layer, not a prediction engine.

Its input shall be structured JSON such as:

{
  "symbol": "BTCUSDT",
  "regime": "Momentum Burst",
  "kinematic": 0.00042,
  "painPath": 0.00031,
  "alpha": 0.00028,
  "tsfm": 0.00051,
  "lobConfidence": 0.87,
  "liquidationBias": "UPPER",
  "coneBreaches": 2
}

The SLM shall generate:

- institutional execution brief;
- model-convergence explanation;
- regime explanation;
- anomaly warning;
- post-session audit;
- model disagreement explanation.

It shall never modify the underlying numerical prediction directly.


---

29. Quantitative Decision Router

The document's proposed Microsoft-Decision-1 layer shall be treated as a replaceable decision-router interface rather than a hard dependency.

The Android architecture shall define:

DecisionRouter

with support for:

- deterministic rule router;
- lightweight classifier;
- validated neural classifier;
- future Decision-1-compatible model.

Its purpose shall be:

Signal convergence
+
confidence
+
regime
+
uncertainty
+
model disagreement
=
Decision State

Possible outputs:

STRONG LONG
LONG
NEUTRAL
SHORT
STRONG SHORT
NO TRADE

The router shall not override the raw forecasts without recording the reason.


---

30. Needle / Agent Orchestration

The source proposes Needle as the on-device orchestration framework.

The Android application shall therefore define an Agent DAG abstraction regardless of the final orchestration runtime.

Required logical agents:

Ingestion Agent
       ↓
Market-State Agent
       ↓
Quant Vector Agent
       ↓
TSFM Agent
       ↓
Decision Router
       ↓
Risk/Confidence Agent
       ↓
Narrator Agent

Each agent shall have:

- defined inputs;
- defined outputs;
- execution timeout;
- health status;
- version;
- telemetry;
- failure fallback.

No agent shall silently corrupt or replace another agent's output.


---

31. Model Ensemble Health

The dashboard shall expose model health.

For each model:

Model
Latency
Inference Count
MAE
RMSE
Directional Accuracy
Cone Hit Rate
Recent Error
Weight
Confidence
Status

Example:

Tri-Factor       31%     Healthy
Kronos           27%     Healthy
Chronos          21%     Healthy
LOB Neural       14%     Healthy
Bias             7%      Active


---

32. Model Disagreement Engine

A major Android-only feature shall be model disagreement detection.

For example:

Tri-Factor       +0.42%
TSFM             +0.51%
LOB Neural       +0.47%
Liquidity        +0.44%

would indicate convergence.

But:

Tri-Factor       +0.38%
TSFM             -0.12%
LOB Neural       +0.44%
Liquidity        -0.30%

would indicate structural disagreement.

The system shall reduce confidence when disagreement exceeds configurable thresholds.


---

33. Forecast Confidence Score

The final confidence score shall incorporate:

Tri-Factor agreement
TSFM agreement
LOB confidence
Liquidity confidence
Regime confidence
Historical model accuracy
Forecast dispersion
Recent cone breaches
Model disagreement
Data freshness

The confidence score shall be explicitly decomposable.

The UI shall allow the user to see why confidence is high or low.


---

34. Prediction State Machine

Every prediction shall move through:

CREATED
   ↓
ACTIVE
   ↓
TARGET_REACHED
   ↓
VALIDATING
   ↓
SETTLED
   ↓
LEARNING_APPLIED

Alternative states:

PENDING_DATA
INVALIDATED
DATA_GAP
MODEL_FAILURE
CLOCK_ERROR

A prediction shall never disappear silently.


---

35. Data Quality Engine

Before a forecast is accepted, AlphaPulse shall verify:

- exchange connectivity;
- timestamp integrity;
- candle continuity;
- order-book sequence integrity;
- trade sequence integrity;
- stale-data age;
- missing liquidation data;
- model inference validity.

The engine shall expose:

DATA HEALTH: 98%

rather than presenting a forecast as equally reliable under degraded conditions.


---

36. Offline Mode

The deterministic engine shall remain partially functional without Internet access.

Offline functionality:

- historical analysis;
- stored prediction ledger;
- model diagnostics;
- model inference over stored data;
- audit analysis;
- SLM narration;
- backtesting.

Real-time functions shall clearly display:

LIVE DATA UNAVAILABLE

rather than producing apparently live predictions from stale information.


---

37. Room Database

Required core tables:

CoinUniverse
DailyHistory
IntradayKlines
Trades
OrderBookSnapshots
OrderBookDeltas
Liquidations
FundingHistory
OpenInterestHistory
VolumeProfile
ModelPredictions
PredictionLedger
EnsembleWeights
ModelPerformance
RegimeHistory
Session
DataHealth
ModelRegistry

Indexes shall prioritise:

symbol + timestamp
symbol + horizon
symbol + prediction_status
model + symbol + horizon

Retention policies shall be configurable.


---

38. Export System

Automatic browser downloads shall be removed.

The application shall provide:

Export
 ├── CSV
 ├── JSON
 ├── Parquet
 └── Share

Exports shall be user initiated unless explicitly configured otherwise.

The user shall be able to export:

- universe;
- prediction ledger;
- model performance;
- order-book statistics;
- liquidation history;
- session audit;
- ensemble weights.


---

39. User Interface

Jetpack Compose shall replace the HTML/Tailwind UI.

The interface shall preserve the successful information architecture of the SPA.

Always visible

- AlphaPulse status;
- selected asset;
- spot price;
- session timer;
- readiness;
- model ensemble;
- prediction ledger summary.

Collapsible

- Risk Adjusted Metrics;
- Institutional Filters;
- Technical Diagnostics;
- Microstructure;
- Liquidation Map;
- Volume Profile;
- Ensemble Forecast;
- Forecast Confidence;
- Model Diagnostics;
- AI Brief.


---

40. Primary Dashboard

The primary screen shall provide:

┌──────────────────────────────────┐
│ AlphaPulse     LIVE ●            │
│ BTCUSDT         $XX,XXX           │
├──────────────────────────────────┤
│ LTF ✓   MTF ✓   HTF ✓             │
├──────────────────────────────────┤
│ FINAL FORECAST                    │
│ +15m       $XX,XXX                │
│ Confidence 87%                    │
│                                  │
│ ███████████████ LONG              │
├──────────────────────────────────┤
│ Model Convergence                 │
│ Tri-Factor      +0.42%            │
│ TSFM            +0.51%            │
│ LOB             +0.47%            │
│ Pain Path       +0.44%            │
├──────────────────────────────────┤
│ Forecast Cone                     │
├──────────────────────────────────┤
│ Prediction Audit                 │
└──────────────────────────────────┘


---

41. Institutional Explanation Panel

The user shall be able to tap:

Why this forecast?

The application shall display:

Kinematic             +++
Liquidity Pain        +++
Confluence Alpha      ++
TSFM                  +++
LOB Confidence        HIGH
Regime                MOMENTUM
Liquidation Bias      UPPER
Model Agreement       91%

Then the Edge SLM may translate this into a concise explanation.


---

42. Security Requirements

API keys shall never be embedded in source code.

Sensitive credentials shall be stored using:

Android Keystore

The application shall support:

- encrypted credential storage;
- biometric unlock;
- API-key permission restrictions;
- read-only exchange keys where possible;
- no withdrawal permissions;
- credential rotation;
- secure deletion.

The initial application shall be analytics/prediction only.

No automatic trading capability shall be required for the core release.


---

43. API Key Architecture

The application shall separate:

Public Market Data
Private Market Data
Premium Data
AI/Model Assets

Example:

Binance public → no key
Binance private → Keystore
Coinglass → Keystore
Smart Money API → Keystore
Apify → Keystore


---

44. Failure Handling

Every major subsystem shall have a fallback.

Example:

Liquidation API
      ↓ failure
Exchange Force Orders
      ↓ failure
Stored liquidation state
      ↓ failure
Liquidity-only model

Similarly:

TSFM
 ↓ unavailable
Tri-Factor

Neural LOB
 ↓ unavailable
UQ-LOB heuristic

NPU
 ↓ unavailable
GPU

GPU
 ↓ unavailable
CPU

The system shall always expose which fallback is active.


---

45. Performance Requirements

Target requirements:

Component	Target

Universe screening	<5 sec target
L2 processing	≤250 ms where exchange permits
Forecast cycle	≤10 sec
Decision routing	<100 ms target
Lightweight neural inference	<100 ms target
UI frame rate	60 FPS minimum
Database writes	non-blocking
Prediction validation	exact scheduled target
WebSocket recovery	automatic
App restart recovery	automatic


The <5 second universe scan shall be treated as a target, not a guaranteed requirement, because exchange rate limits and network conditions remain external constraints.


---

46. Battery Requirements

The system shall distinguish:

Live Quant Mode

Maximum real-time capability.

Balanced Mode

Reduced order-book sampling and inference frequency.

Analysis Mode

No continuous WebSockets.

Offline Mode

No network.

The user shall be able to select the mode.


---

47. Model Download Architecture

Models shall not necessarily ship inside the APK.

The application shall use:

Base APK
   ↓
Model Manager
   ↓
Download selected model
   ↓
Verify checksum
   ↓
Validate runtime compatibility
   ↓
Install
   ↓
Benchmark
   ↓
Activate

The Model Manager shall maintain:

Model ID
Version
Parameter count
Quantization
Size
License
Backend
Latency
Accuracy
Checksum
Status


---

48. Model Selection Benchmark

On first installation, the application shall benchmark compatible models using a fixed local validation set.

Example:

Model              Size     Latency    RMSE     Direction
Chronos Tiny       8M       12ms       X        X%
Chronos Mini       20M      24ms       X        X%
Chronos Small      46M      51ms       X        X%
Kronos Small       X        X          X        X%
TimesFM            X        X          X        X%

The application shall select the production model based on measured validation performance, not model size alone.


---

49. Critical Model Selection Principle

The Android application shall not assume that a finance-pretrained equity model is automatically superior for crypto intraday forecasting.

Crypto perpetuals have characteristics including:

- 24/7 trading;
- funding;
- liquidation cascades;
- perpetual OI;
- exchange-specific microstructure;
- high-frequency order-book dynamics.

Therefore all candidate TSFMs shall be subjected to:

Binance historical walk-forward testing
+
Bybit validation
+
multiple symbols
+
all five horizons

before production activation.


---

50. Walk-Forward Validation

The application shall support an offline research mode capable of:

Train/Calibrate
       ↓
Walk Forward
       ↓
Predict
       ↓
Observe
       ↓
Update
       ↓
Repeat

Metrics shall include:

- MAE;
- RMSE;
- MAPE where meaningful;
- directional accuracy;
- signed error;
- cone coverage;
- calibration error;
- prediction latency;
- model stability;
- turnover sensitivity.


---

51. No Data Leakage Requirement

Historical backtests shall enforce strict temporal ordering.

At prediction timestamp T, no information after T may be used.

This applies to:

- candles;
- OI;
- liquidations;
- volume profile;
- order book;
- model calibration;
- ensemble weights;
- feature normalization.

This shall be a mandatory test requirement.


---

52. Testing Requirements

The application shall use TDD.

Required test groups:

Unit

- institutional filters;
- Calmar;
- Sharpe;
- kurtosis;
- GARCH;
- FRVP;
- VWAP;
- UQ-LOB;
- liquidity path;
- ensemble weights;
- confidence cone.

Integration

- Binance;
- Bybit;
- Hyperliquid;
- WebSocket reconstruction;
- Room;
- ML runtimes;
- model manager.

Quantitative

- walk-forward;
- leakage detection;
- forecast calibration;
- model comparison.

Resilience

- network loss;
- exchange outage;
- WebSocket sequence gaps;
- API rate limiting;
- device lock;
- app restart;
- battery optimisation;
- process death.


---

53. Android Lifecycle Testing

The following sequence shall be explicitly tested:

Start session
↓
Lock phone
↓
Wait 30 minutes
↓
Unlock
↓
Verify all scheduled predictions
↓
Verify validation timestamps
↓
Verify ledger completeness
↓
Verify weights

This specifically addresses the HTML5 problem where background throttling caused missing validations.


---

54. Session Recovery

If Android terminates the process:

App restarts
     ↓
Read Room
     ↓
Recover active predictions
     ↓
Recover ensemble weights
     ↓
Recover order-book state where possible
     ↓
Rebuild missing market state
     ↓
Resume

The session shall not simply reset to baseline.


---

55. Model Versioning

Every prediction shall be linked to:

Quant Engine Version
TSFM Version
LOB Model Version
Decision Router Version
SLM Version
Feature Schema Version

This makes historical audit scientifically reproducible.


---

56. Observability

The application shall expose an engineering/quant diagnostics screen showing:

WebSocket latency
REST latency
Data freshness
Dropped messages
Order-book sequence gaps
CPU utilisation
GPU utilisation
NPU utilisation
Memory
Battery
Inference latency
Database latency
Model errors

This is particularly important because the application is intended to operate as an edge quantitative workstation.


---

57. Notifications

High-priority notifications shall be generated only when configurable convergence conditions are satisfied.

Example:

BTCUSDT
STRONG LONG CONVERGENCE

Tri-Factor: LONG
TSFM: LONG
LOB: LONG
Liquidity: LONG

Confidence: 91%
15m Forecast: +0.72%

Notifications shall also support:

Cone breach
Regime transition
Liquidity shock
Model disagreement
Data failure
Exchange failure


---

58. Notification Veto

The system shall suppress high-priority signals when:

LOB uncertainty is extreme
+
model disagreement is high

or when:

data freshness < threshold

or:

required model unavailable

The reason shall be logged.


---

59. AI Narrator Requirements

The narrator shall never invent numerical facts.

All numerical claims must originate from the structured engine state.

The narrator shall be explicitly constrained to:

Explain
Summarize
Diagnose
Compare

and shall not:

Invent data
Change model outputs
Change ensemble weights
Create unsupported probabilities


---

60. Research Mode

A dedicated Research Mode shall allow the user to compare models.

Example:

BTCUSDT / 15m

Tri-Factor       +0.31%
Kronos           +0.46%
Chronos          +0.38%
TimesFM          +0.22%
LOB Neural       +0.41%

Ensemble         +0.37%

The user shall be able to inspect historical performance of every component.


---

61. Recommended Final Architecture

The production architecture should therefore be:

ALPHAPULSE ANDROID
                           │
                     Jetpack Compose
                           │
                    ViewModel / StateFlow
                           │
                 ┌─────────┴──────────┐
                 │                    │
             Quant Core          AI Core
                 │                    │
       ┌─────────┼─────────┐    ┌─────┼─────┐
       │         │         │    │     │     │
   Kinematic  Pain Path  Alpha TSFM  LOB   SLM
       │         │         │    │     │     │
       └─────────┴─────────┴────┴─────┘
                           │
                    Ensemble Engine
                           │
                    Learning Engine
                           │
                    Decision Router
                           │
                     Risk Gate
                           │
                    Prediction Ledger
                           │
                         Room
                           │
                  Persistent Foreground
                       Service
                           │
             Binance / Bybit / Hyperliquid


---

62. Release Strategy

Release 1: Native Parity

Reproduce the HTML5 functionality:

- institutional scanner;
- Calmar;
- Sharpe;
- Tri-Factor;
- L2;
- five horizons;
- prediction ledger;
- RMSE calibration;
- background service;
- Room;
- CSV export.

Release 2: Quant Upgrade

Add:

- full order-book reconstruction;
- liquidation heatmap;
- institutional trade tape;
- horizon-specific pain paths;
- 24h FRVP;
- VAH/VAL/LVN;
- neural UQ-LOB;
- improved regime model.

Release 3: Multi-Model Intelligence

Add:

- Kronos;
- FinText Chronos;
- TimesFM;
- model benchmarking;
- fourth-factor ensemble;
- persistent per-coin/per-horizon learning.

Release 4: Edge Intelligence

Add:

- Edge SLM;
- decision router;
- agent DAG;
- model disagreement;
- autonomous quantitative briefs;
- advanced notifications.


---

63. Definition of Done

The Android application shall not be considered a successful replacement for the SPA merely because it reproduces the UI.

It shall be considered complete when it can:

1. continuously ingest market data;
2. screen the futures universe;
3. reconstruct order books;
4. track institutional trade flow;
5. construct liquidation maps;
6. calculate the original Tri-Factor model;
7. calculate horizon-specific liquidity paths;
8. execute neural microstructure models;
9. execute at least one validated TSFM;
10. generate five-horizon probabilistic forecasts;
11. combine independent models dynamically;
12. persist model weights per asset and horizon;
13. validate every prediction;
14. learn from every settled prediction;
15. survive screen lock and background execution;
16. recover after process termination;
17. maintain a complete audit ledger;
18. operate without exposing API credentials;
19. explain its numerical state locally;
20. provide measurable evidence that the Android models outperform or add incremental information beyond the original Tri-Factor engine.


---

64. Most Important Architectural Change

The final AlphaPulse Android engine should therefore not be:

HTML5 SPA
      ↓
Android port
      ↓
Add LLM

It should be:

ALPHAPULSE V14.3
                  │
                  ▼
        Native Quant Core
                  │
       ┌──────────┴───────────┐
       │                      │
 Existing Tri-Factor       Deep Market
       │                    Microstructure
       │                      │
       └──────────┬───────────┘
                  │
                  ▼
        Independent TSFM Layer
                  │
       Kronos / FinText / Chronos
                  │
                  ▼
          Fourth Forecast Vector
                  │
                  ▼
       Adaptive Ensemble Learning
                  │
                  ▼
        Probabilistic Forecast
                  │
          ┌───────┴────────┐
          │                │
    Decision Router    Edge SLM
          │                │
          └───────┬────────┘
                  ▼
          AlphaPulse Android

That distinction is important: the TSFM should be an independent forecaster, not an AI decoration around the existing predictor. The existing Tri-Factor engine can then prove, through the Prediction Audit Ledger, whether the new model actually contributes incremental predictive value.

For the initial implementation, I would make Kronos + FinText Chronos Small + the existing Tri-Factor engine the primary research comparison set, with TimesFM retained as an additional benchmark. FinText's finance-pretrained Chronos Small is only 46.2M parameters, while the generic Chronos family also provides 8M and 20M variants for an aggressive mobile-performance tier. 

I would also make model benchmarking a hard requirement rather than selecting a model by reputation. The Android app should empirically determine, per symbol and horizon, whether the fourth TSFM factor actually improves out-of-sample forecasting over the existing Tri-Factor engine.


Appendix A: On-Device AI/ML Model Catalogue and Sources

A.1 Purpose

The AlphaPulse Android application shall use a layered on-device AI architecture rather than relying on a single general-purpose LLM.

The recommended hierarchy is:

Market data → Quantitative models → Time-Series Foundation Model → Decision/Regime model → Edge SLM → Human-readable explanation

The models shall therefore be divided into:

Primary price-prediction models

Kronos

FinText/Chronos

Chronos-Bolt

Chronos

TimesFM

Microstructure/regime models

AGA-Neural HMM

UQ-LOB CNN

Microsoft Decision-1, subject to verification of a suitable publicly deployable model/checkpoint

Narrative/explanation models

Llama 3.2 1B Instruct

Gemma 2 2B IT

Phi-3.5-mini Instruct

The application must not use the conversational SLM as the primary numerical price predictor.

A.2 Recommended Primary Forecasting Models

PriorityModelParametersPrimary roleAlphaPulse suitability1Kronos-small24.7MFinancial/K-line forecasting⭐⭐⭐⭐⭐2FinText Chronos-Small46.2MFinance-pretrained TS forecasting⭐⭐⭐⭐⭐3Chronos-Bolt-small48MFast probabilistic multi-step forecasting⭐⭐⭐⭐⭐4Chronos-Bolt-mini21MLightweight probabilistic forecasting⭐⭐⭐⭐½5FinText Chronos-Mini/Tiny20M / 8MUltra-light financial forecasting experiments⭐⭐⭐⭐6TimesFMModel-dependentIndependent structural forecast benchmark⭐⭐⭐⭐7Generic Chronos-Small46MGeneral TS forecasting benchmark⭐⭐⭐⭐ 

A.2.1 Kronos-small

Recommended model:

NeoQuasar/Kronos-small

Parameters: 24.7M
Context: 512
Source: Hugging Face

Kronos is particularly relevant to AlphaPulse because it was specifically designed for financial market time series and uses a market-oriented tokenizer. Its model family was pretrained on more than 12 billion K-line records from 45 global exchanges. The published model zoo lists Kronos-small at 24.7M parameters. 

Android requirement:

The application shall evaluate Kronos-small as the first-choice fourth forecasting vector:

Kinematic μk + Liquidity Pain μp + Confluence Alpha μα + Kronos μK ↓ Adaptive Ensemble ↓ 5 Forecast Horizons 

The Kronos forecast shall remain independent from the existing three AlphaPulse vectors so that the audit ledger can determine whether it contributes genuine incremental predictive information.

A.3 FinText Chronos

A.3.1 FinText Chronos-Small

Recommended model:

FinText/Chronos_Small_2023_US

Parameters: 46.2M
Format: Safetensors
License: Apache 2.0

This model uses the Chronos architecture and was pretrained on U.S. financial time-series data covering 1990 to 2023. The Hugging Face model card reports 46.2M parameters. 

Important: it is an equity/financial model rather than a crypto-specific model. Therefore it shall be treated as an experimental benchmark, not assumed to be superior to Kronos for BTC/USDT or altcoins.

A.3.2 Global FinText Chronos

An additional candidate is:

FinText/Chronos_Small_2021_Global

Parameters: 46.2M

It was pretrained on global financial time-series data and is therefore potentially more appropriate than the U.S.-only variant as a cross-market benchmark. 

A.4 Chronos and Chronos-Bolt

The official Chronos repository currently provides a useful range of extremely small models:

ModelParametersChronos-T5 Tiny8MChronos-T5 Mini20MChronos-T5 Small46MChronos-Bolt Tiny9MChronos-Bolt Mini21MChronos-Bolt Small48MChronos-2 Small28MChronos-2120M 

The published repository lists these parameter counts. 

A.4.1 Chronos-Bolt

Chronos-Bolt is particularly interesting for Android because it uses a patch-based architecture and directly generates multi-step quantile forecasts. The project reports substantially lower inference cost compared with the original Chronos architecture. 

Recommended Android candidates:

Chronos-Bolt Tiny → 9M Chronos-Bolt Mini → 21M Chronos-Bolt Small → 48M 

The Android application should initially benchmark Bolt Mini and Bolt Small.

Bolt Tiny should be retained as the ultra-low-resource fallback.

A.5 TimesFM

The requirements document currently refers to TimesFM as a lightweight model. This should be revised.

The current publicly available TimesFM 2.0 checkpoint identified for this architecture is:

google/timesfm-2.0-500m-pytorch

Parameters: approximately 500M
Checkpoint size: approximately 3.99 GB in the listed PyTorch/Safetensors repository. 

Therefore:

TimesFM should NOT be treated as a first-generation small on-device model for AlphaPulse.

It should instead be an optional high-capability benchmark, subject to quantization and successful Android hardware testing.

TimesFM 2.0 supports context lengths up to 2,048 time points and provides point forecasting with optional quantile heads. 

Android requirement

The architecture shall support TimesFM through a pluggable TimeSeriesModelProvider interface, but the initial production APK shall not require TimesFM.

This prevents a ~500M-parameter model from becoming a hard dependency.

A.6 Recommended Edge SLMs for Explanation

These models are not the primary price predictors.

Their role is to convert AlphaPulse's structured quantitative state into a human-readable explanation.

For example:

{ "symbol": "BTCUSDT", "regime": "MOMENTUM_BURST", "mu_k": 0.00031, "mu_p": 0.00017, "mu_alpha": 0.00029, "mu_tsfm": 0.00034, "l2_imbalance": 0.71, "vwap_distance": 0.0028, "cone_breach_rate": 0.18 } 

The SLM converts this into something like:

Momentum regime remains dominant. Kronos and Confluence Alpha agree on upside continuation while the liquidity vector provides secondary confirmation. However, elevated L2 uncertainty is reducing confidence.

The SLM must not be permitted to modify numerical forecasts.

A.6.1 Llama 3.2 1B Instruct

Recommended role: Primary lightweight narrative SLM.

Parameters: ~1B

This is the preferred starting point for the AlphaPulse explanation layer.

ExecuTorch specifically provides Android support for LLM inference, including Java/Kotlin APIs. 

Importantly, the model should be quantized before inclusion in the APK. The published ExecuTorch Android measurements demonstrate that quantization can substantially reduce model size and improve inference speed on Android hardware. 

A.6.2 Gemma 2 2B IT

Model:

google/gemma-2-2b-it

Parameters: approximately 2.6B

Google's model collection identifies the Gemma 2 2B architecture as the 2.6B-parameter version. 

Role:

Secondary/high-quality explanation model.

It should be used when the device has sufficient RAM and when explanation quality is prioritized over minimum resource consumption.

The model should be deployed in a quantized format rather than the original full-precision checkpoint.

A.6.3 Phi-3.5-mini-Instruct

Model:

microsoft/Phi-3.5-mini-instruct

Parameters: 3.8B

Microsoft's documentation identifies Phi-3.5-mini as a 3.8B-parameter model. 

Role:

Optional high-quality offline analyst/narrator.

It should not be included in the minimum APK configuration because its resource requirements are substantially higher than Llama 3.2 1B.

A.7 Final Recommended Android AI Stack

The requirements should therefore define three deployment profiles.

Profile 1: Minimum / Ultra-Light

Designed for devices with limited RAM or aggressive battery constraints.

AlphaPulse Quant Engine │ ├── Tri-Factor Engine ├── Kronos-mini / Chronos-Bolt-Tiny └── Small Edge SLM └── Llama 3.2 1B 4-bit 

Kronos-mini is only 4.1M parameters, while Chronos-Bolt Tiny is 9M. 

Profile 2: Recommended Production

This should be the default AlphaPulse Android configuration.

┌── Kinematic │ Market Data ──────┼── Liquidity Pain │ ├── Confluence Alpha │ ├── Kronos-small │ └── Chronos-Bolt-mini/small │ ▼ Adaptive Ensemble │ Online Calibration │ ┌─────────────┴─────────────┐ ▼ ▼ Decision/Regime Confidence Cone Engine │ │ │ └────────────┬─────────────┘ ▼ Llama 3.2 1B │ ▼ Institutional Brief 

Profile 3: High-End Research Device

Tri-Factor + Kronos-small + FinText Chronos-small + Chronos-Bolt-small + TimesFM 2.0 + UQ-LOB + AGA-HMM ↓ Multi-model consensus ↓ Online calibration ↓ Edge SLM 

This configuration should be explicitly labelled Research Mode and must not assume that adding models automatically improves prediction accuracy.

A.8 Model Storage vs Parameter Count

A critical Android requirement is that parameter count must not be interpreted as APK/storage size.

For example:

46M parameters × FP32 ≈ 184 MB weights 46M parameters × FP16 ≈ 92 MB 46M parameters × INT8 ≈ 46 MB 46M parameters × INT4 ≈ 23 MB 

Actual deployed size will differ because of tokenizer data, runtime metadata, tensor layouts, operators and runtime libraries.

Therefore every model shall have two recorded attributes:

parameter_count deployment_weight_size 

and the build system shall report both.

A.9 Model Discovery and Download Requirements

The application shall not hard-code model binaries directly into the source repository.

Instead:

Model metadata shall be maintained in a versioned manifest.

Models shall be downloaded from their official/public repositories.

SHA-256 hashes shall be verified before activation.

Model versions shall be pinned.

Quantized variants shall be explicitly identified.

A model shall not be replaced silently.

Failed model downloads shall not prevent AlphaPulse's deterministic quantitative engine from operating.

The application shall maintain a ModelRegistry in Room.

Example:

ModelRegistry ──────────────────────────────────── model_id model_family version parameter_count quantization source_url sha256 file_size runtime hardware_target enabled validation_score last_tested 

A.10 Runtime Selection

The Android application shall expose:

Settings → AI Engine → Model Configuration

with:

Forecasting Models ──────────────────────────── ☑ Kronos-small ☑ Chronos-Bolt-mini ☐ FinText Chronos-small ☐ TimesFM 2.0 Explanation Model ──────────────────────────── ● Llama 3.2 1B ○ Gemma 2 2B ○ Phi-3.5 Mini Hardware ──────────────────────────── ● Auto ○ CPU ○ GPU ○ NPU/Accelerator 

The Auto option shall benchmark supported execution backends and select the most appropriate runtime.

A.11 Important Architectural Rule

The final Android application should not become "an LLM trading app."

It should remain:

A deterministic quantitative forecasting engine augmented by independent neural/time-series models and an offline SLM explanation layer.

The numerical decision path should therefore be:

Market data → deterministic quantitative models → TSFMs → calibrated ensemble → uncertainty → decision gate

while the language model sits after the quantitative decision layer:

Quantitative state → SLM → explanation

This separation is particularly important because it prevents a generative model from inventing a price prediction that is not supported by the actual market telemetry.

Recommended production model set

If we have to choose the initial set today, I would put these into the Android requirements as the mandatory evaluation candidates:

ComponentModelSizeFinancial TSFM #1Kronos-small24.7MTSFM #2Chronos-Bolt-mini21MTSFM #3FinText Chronos-Small46.2MUltra-light TSFMChronos-Bolt-Tiny9MResearch benchmarkTimesFM 2.0500MEdge narratorLlama 3.2 1B Instruct~1BAlternative narratorGemma 2 2B IT~2.6BHigh-quality narratorPhi-3.5 Mini Instruct3.8B 

The key change I would make to the original document is therefore Kronos-small + Chronos-Bolt as the core price-prediction candidates, with TimesFM moved into an optional research tier. The available model specifications support this much better than treating all of these models as equivalent "LLMs." 

