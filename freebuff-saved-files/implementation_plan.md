# AlphaPulse: Full Institutional Quantitative Specification & 24/7 Background Workstation Plan

A comprehensive engineering blueprint to elevate the native Android AlphaPulse app into an autonomous institutional quantitative workstation adhering to all architectural specifications, the strict 5-decimal price formatting rule, 24/7 foreground background execution with live notification telemetry, real-time WebSocket order-flow ingestion, automated 4-tab universe audit export, and full HTML5 SPA parity.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Key Confirmed Requirements:**
> 1. **Strict 5-Decimal Precision Formatting**: All coin prices shown across the entire app must be accurate to and display only **up to 5 decimal places** (e.g. `"$0.07731"`, `"$22.24000"`). This applies to Spot Price, 24h High/Low, Screened Dropdown labels, Tri-Factor Target Forecasts (+1$\sigma$ Cap / -1$\sigma$ Floor), Trajectory Chart axis labels, Prediction Audit Ledger (Predicted / Realized / Error), Background Notification Drawer, and CSV exports.
> 2. **24/7 Background Workstation**: Persistent Android Foreground Service (`AlphaPulseService`) with partial `WakeLock` and ongoing status notification displaying live spot price, active countdown timer, and Markov regime state.
> 3. **Full Attachment Specification Integration**: Incorporates all Section 4 & Section 5 tasks: WebSocket `@depth20@250ms` and continuous `@aggTrade` tape buffer, discrete horizon liquidity pain paths (1m, 5m, 15m, 30m, 1h), 24h tick volume profile (POC / VAH / VAL with 80% rotation rule), dynamic perpetual funding rate for $R_f$, automated 4-tab universe CSV export, phase-locked candle boundary NTP timestamps (:00.000s), and Needle on-device Quant Narrative agent.

---

### 1. Overview & Core Concept

- **What It Does**: AlphaPulse is an autonomous institutional quantitative crypto screener and tri-factor predictive forecasting workstation running natively on Android. It scans the Binance USDT perpetual futures universe (250+ pairs), applies a 3-step institutional filtering pipeline (liquidity, taker volume, Calmar ratio, and dynamic Sharpe ranking), streams real-time Level 2 depth and trade flow via WebSockets, computes Tri-Factor forecast cones across 5 intraday horizons (1m, 5m, 15m, 30m, 1h), and tracks performance in a local Room SQLite ledger with online SGD weight adaptation.
- **Target Audience / Persona**: Institutional quantitative traders, proprietary crypto funds, and active algorithmic market participants requiring high-precision telemetry, microstructural order-book analysis, and continuous 24/7 background operation.
- **Key Value**: Delivers desktop-grade quantitative screening and real-time predictive modeling directly on an Android device with zero dropped ticks, battery-optimized background persistence, and strict institutional numerical accuracy.

---

### 2. User Experience & Visual Design

- **Key User Flows**:
  1. *Screening & Universe Discovery*: User launches app or background service triggers auto-screen. The animated scanner progress banner visualizes the 3-step pipeline across all 250+ pairs. The top 10 assets populate the interactive dropdown selector with strict 5-decimal pricing.
  2. *Live Workstation Monitoring*: The top workspace displays the selected asset's spot price (5 decimals), 24h change, high/low, regime indicator, session elapsed timer, and readiness badges (LTF, MTF, HTF).
  3. *Tri-Factor Target Horizons*: The user views 5 interactive horizon cards (1m, 5m, 15m, 30m, 1h) showing predicted target prices, 68.2% ($\pm 1\sigma$) confidence envelopes, and countdown timers synced to candle boundaries (:00.000s).
  4. *Microstructure & Trajectory Visualization*: A hardware-accelerated Canvas chart plots the historic price curve, projected trajectory, and confidence cones with 5-decimal right-axis labels. Accordion sections reveal Risk-Adjusted Metrics, Technical Diagnostics (POC, VAH, VAL, VWAP), Institutional Filter Badges, and the Needle Cognitive Brief.
  5. *Prediction Audit Ledger & CSV Export*: A live ledger records all issued predictions, compares against realized settlement prices with exact dollar errors, and highlights in-cone accuracy. Users can export or auto-save the comprehensive 4-tab universe audit CSV.
  6. *Background Monitoring*: When minimized, an ongoing system drawer notification updates with the live spot price, active countdown, and Markov regime.

- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Dark institutional terminal aesthetic inspired by Bloomberg and quantitative hedge fund workstations.
  - *Color Palette*:
    - Background: Deep Void (`#080A0F`)
    - Surface / Cards: Dark Obsidian (`#101520`, `#161D2B`)
    - Borders: Subtle Slate (`#232D42`)
    - Accents: Electric Cyan (`#3B82F6`), Neon Violet (`#8B5CF6`), Emerald Bullish (`#10B981`), Crimson Bearish (`#EF4444`), Amber Warning (`#F59E0B`)
  - *Typography*: High-legibility monospaced figures (`FontFamily.Monospace`) for prices, percentages, and timestamps; clean geometric sans-serif for headers and labels.
  - *Interactive Feedback*: Smooth pulse animations for live WebSocket connectivity, progress bar easing during scans, tactile card selection feedback, and animated countdown timers.

---

### 3. Key Product Decisions & Trade-Offs

- **Decision 1: Up-To 5 Decimal Places Price Formatting Standard**:
  - *Chosen Approach*: Centralized `formatPrice5Dec(Double): String` utilizing `String.format(Locale.US, "$%.5f", price)` and `formatRawPrice5Dec(Double): String` for raw values without currency symbols.
  - *Why*: Guarantees consistent precision across high-priced assets (e.g. Bitcoin at `$64520.12345`) and micro-cap futures (e.g. `$0.07731`) without awkward scientific notation or truncation.
  - *Alternatives Considered*: Dynamic decimal scaling (2 decimals for >$1, 6 decimals for <$0.01) was rejected because the user specifically mandated up-to 5 decimal places across all screens.

- **Decision 2: Persistent Foreground Service with Partial WakeLock**:
  - *Chosen Approach*: Implement `AlphaPulseService` with `FOREGROUND_SERVICE_DATA_SYNC` and `PowerManager.PARTIAL_WAKE_LOCK`.
  - *Why*: Android OS aggressively suspends background network sockets and timers when the screen is locked. A foreground service with a partial wake lock keeps the WebSocket alive, prevents clock drift for prediction settlements, and satisfies 24/7 non-stop execution.
  - *Alternatives Considered*: WorkManager periodic tasks (minimum 15-minute intervals, cannot maintain 250ms WebSockets or 10s cycles).

- **Decision 3: Multi-Endpoint Resilient REST & WebSocket Architecture**:
  - *Chosen Approach*: Multi-mirror failover (Binance USD-M Futures -> Spot API -> Binance Vision Mirror -> Coinglass API) paired with OkHttp WebSocket streaming for depth and trade flows.
  - *Why*: Resolves the `/fapi/v1/ticker/24hr` unreachable errors caused by geographic or endpoint throttling, while offloading high-frequency order-book updates to low-overhead WebSockets.

---

### 4. Technical Architecture & System Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│               ALPHAPULSE PERSISTENT FOREGROUND SERVICE                 │
│      (Foreground Service + Partial WakeLock + Live System Drawer)      │
│     * Live Notification: Spot (≤5 dec) | Target Timer | Regime Status  │
└───────────────────┬────────────────────────────────┬───────────────────┘
                    │                                │
                    ▼                                ▼
┌──────────────────────────────────────┐  ┌──────────────────────────────┐
│  LIVE WEBSOCKET STREAMING ENGINE     │  │  RESILIENT REST ENGINE       │
│  • @depth20@250ms L2 Book Stream     │  │  • 15-Worker Universe Batch  │
│  • @aggTrade 24h Tape Circular Buffer│  │  • Multi-mirror Futures      │
│  • Instant Order Flow Imbalance (OFI)│  │  • /api/v3/ Spot Mirror      │
│  • Real-time Liquidity Turnover (λ)  │  │  • Coinglass & SmartMoney    │
└───────────────────┬──────────────────┘  └──────────────┬───────────────┘
                    │                                    │
                    └──────────────────┬─────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   QUANTITATIVE ENGINE & NEEDLE AGENT                   │
│   • 60s 3-Step Institutional Screening (All 250+ active USDT pairs)    │
│   • Dynamic Perpetual Funding Risk-Free Rate (Rf from 8h funding)      │
│   • Auto-Save 4-Tab Universe Audit CSV (AlphaPulse_Filtered_Universe)  │
│   • Discrete Horizon Liquidity Shelves (1m, 5m, 15m, 30m, 1h)          │
│   • 24h Volume Profile: POC, 70% VAH/VAL, 80% Rotation Trigger         │
│   • Phase-Locked Candle Boundary NTP Timestamps (:00.000s)             │
│   • Online SGD / Standardized Z-Score Weights with Per-Coin Room State │
│   • Needle Cognitive Brief: Real-time institutional regime brief       │
└──────────────────────────────────────┬─────────────────────────────────┘
                                       │
                                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│              REACTIVE PRESENTATION & ROOM SQLITE LAYER                 │
│   • Strict Up-To 5 Decimal Places Price Formatting across UI & CSV     │
│   • Room SQLite Database (Screened Coins, Predictions, Weights)        │
│   • Jetpack Compose M3 Dark UI (Header, Workspace, Canvas Chart)       │
│   • Prediction Audit Ledger with live countdowns & In-Cone/Breach      │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 5. Concrete Implementation Steps

#### Step 1: Up-To 5 Decimal Places Price Formatting System
- Implement `com.example.ui.util.Formatters`:
  - `formatPrice5Dec(value: Double): String` -> `"$12.34567"`
  - `formatRawPrice5Dec(value: Double): String` -> `"12.34567"`
- Replace every price formatting occurrence across Composables:
  - `TopWorkspaceCards.kt`: Spot price, 24h high, 24h low.
  - Dropdown menu items: `1. BTCUSDT ($64520.12345)`.
  - Horizon Target Cards: Predicted price, +1$\sigma$ upper bound, -1$\sigma$ lower bound.
  - `ForecastTrajectoryChart.kt`: Right price scale axis labels and current price indicator.
  - `PredictionLedgerSection.kt`: Target price, realized price, absolute dollar error.
  - `AccordionsSection.kt`: POC, VAH, VAL, VWAP, best bid/ask.
  - CSV export columns.

#### Step 2: 24/7 Persistent Background Service (`AlphaPulseService`)
- Create `com.example.service.AlphaPulseService`:
  - Inherits from Android `Service`.
  - Acquires `PowerManager.PARTIAL_WAKE_LOCK`.
  - Configures `NotificationChannel` with high priority.
  - Dispatches ongoing notification with live spot price, target countdown, and regime.
  - Binds repository cycle and WebSocket stream so evaluation continues in the background.
- Declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` in `AndroidManifest.xml`.
- Add auto-start and foreground service lifecycle handling in `MainActivity.kt`.

#### Step 3: Real-Time Level 2 Depth & Continuous AggTrade WebSocket Stream
- Create `com.example.data.network.BinanceWebSocketManager`:
  - OkHttp WebSocket connection to `wss://fstream.binance.com/ws/${symbol.lowercase()}@depth20@250ms`.
  - Computes instantaneous Order Flow Imbalance (OFI) and bid/ask volume pressure.
  - Subscribes to `${symbol.lowercase()}@aggTrade` to maintain a circular buffer of recent trades, extracting the empirical 95th-percentile institutional shock size dynamically.
  - Dynamically computes liquidity turnover $\lambda = \text{clamp}(\frac{|\Delta D|}{D \cdot \Delta t}, 0.01, 0.15)$.

#### Step 4: Discrete Horizon Liquidity Pain Paths & 24h Volume Profile
- In `QuantMath.kt`:
  - Horizon-coupled pain paths: 1m/5m mapped to immediate L2 book voids; 15m/30m mapped to intraday liquidation bands and POC gravity; 1h mapped to session high/low stop-run clusters and Value Area extremes.
  - 24-hour Volume Profile across 50 discrete price bins to determine POC, VAH (70%), and VAL (70%).
  - 80% Auction Market Value Area Rotation rule: If price opens outside the Value Area and accepts back inside for 2 consecutive periods, set target to opposite Value Area boundary.

#### Step 5: Dynamic Perpetual Funding Risk-Free Rate ($R_f$)
- In `ResilientBinanceClient.kt`:
  - Fetch 8-hour perpetual funding rate from `/fapi/v1/fundingRate?symbol=BTCUSDT&limit=1`.
  - Calculate annualized risk-free rate: $R_f = \text{lastFundingRate} \times 3 \times 365$ (fallback to 0.04 if unavailable).
  - Feed dynamic $R_f$ into Sharpe ratio calculation in Step 3 of the screening pipeline.

#### Step 6: Automated 4-Tab Universe Audit CSV Export
- In `AlphaPulseRepository.kt`:
  - Upon completion of the 60s screening cycle, automatically generate and save a multi-tab formatted CSV to app cache:
    - Tab 1: Initial Universe (Symbol, Price, High, Low, Volume, Change %, Taker Buy %, 10D Pass Count).
    - Tab 2: Institutional Filtered (Symbol, Price, Volume, Exact OI, Vol/OI, Taker Buy %, Displacement %, Status).
    - Tab 3: Calmar Filtered (Symbol, 1Y Return %, Ann Return %, Max DD %, Calmar Ratio).
    - Tab 4: Sharpe Ranked (Rank, Symbol, Sharpe Ratio, Calmar Ratio, Ann Vol %, Ann Return %).
  - Ensure all price values are formatted to 5 decimal places.
  - Provide a share/export button in the UI using Android `FileProvider`.

#### Step 7: NTP Candle Boundary Phase-Locking (:00.000s)
- Synchronize target timestamps to round minute boundaries:
  - $\text{TargetTimestamp} = (\lfloor\frac{\text{SystemTime}}{60000}\rfloor + \text{mins}) \times 60000$.
  - Guarantees prediction countdowns align precisely with the session elapsed timer and candle closes.

#### Step 8: Needle Quant Narrative Agent Enhancement
- Enhance `QuantNarrativeAgent.kt`:
  - Produce real-time institutional cognitive briefs detailing dominant vector weights ($W_k, W_p, W_\alpha$), Markov regime transitions, Value Area positioning, and L2 micro-imbalances.

#### Step 9: Verification & APK Delivery
- Execute JVM and Robolectric unit tests via `gradle :app:testDebugUnitTest`.
- Compile and assemble the debug APK via `gradle assembleDebug`.
- Copy APK to root directory (`AlphaPulse.apk`) for download and verify emulator display.
