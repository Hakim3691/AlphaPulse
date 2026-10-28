# AlphaPulse ⚡
### Autonomous Institutional Quantitative Crypto Screener & Tri-Factor Predictor

AlphaPulse is a native Android institutional quantitative workstation that streams high-frequency Level-2 order book depth, screens 250+ Binance USDT perpetual futures contracts across a 3-stage quantitative pipeline, evaluates intraday Tri-Factor prediction cones (1m, 5m, 15m, 30m, 1h), and maintains non-stop 24/7 background execution with live telemetry notifications.

> [!IMPORTANT]
> **Functional Specifications & Architecture References**:
> - **HTML5 Specification (V14.3)**: [`AlphaPulse_HTML5_SPEC_V14-3.md`](./AlphaPulse_HTML5_SPEC_V14-3.md)
> - **Android Specification (V14.3)**: [`AlphaPulse_Android_SPEC_V14-3.md`](./AlphaPulse_Android_SPEC_V14-3.md)
> - **Native Android Master PRD & Architecture**: [`AlphaPulse_Native_Android_PRD.md`](./AlphaPulse_Native_Android_PRD.md)
> - **Spec Traceability Matrix** (every § of the Android spec mapped to code and tests): [`SPEC_TRACEABILITY.md`](./SPEC_TRACEABILITY.md)
> - **Finalized Contracts** (decisions that are locked and guarded — read before changing routing, the model pipeline, the build or the ledger): [`FINALIZED_CONTRACTS.md`](./FINALIZED_CONTRACTS.md)
> - **Contributor & Agent Instructions** (BDD first, local gates, CI/CD, tag releases): [`AGENTS.md`](./AGENTS.md)
> - **HTML5 Baseline Implementations**: [`AlphaPulse_HTML5_IMPL_V14-3.md`](./AlphaPulse_HTML5_IMPL_V14-3.md), [`AlphaPulse_HTML5_IMPL_V14-2.md`](./AlphaPulse_HTML5_IMPL_V14-2.md), [`AlphaPulse_HTML5_IMPL_V14-0.md`](./AlphaPulse_HTML5_IMPL_V14-0.md)


---

## 🏛️ Core Architecture & Concept

AlphaPulse is engineered to bring desktop-grade quantitative research, order-book microstructural analysis, and real-time machine-learning model adaptation directly to an Android device.

* **3-Step Institutional Screening Pipeline**:
  1. **Liquidity & Microstructure Filter**: Filters out illiquid pairs by evaluating 24h quote volume ($>\$1\text{M}$), Open Interest ($>\$1\text{M}$), Vol/OI ratio, taker buy ratio, and daily price displacement. Applies a strict 10-day historical consistency rule.
  2. **1-Year Calmar Ratio Filter**: Evaluates 365-day historical daily kline series to compute 1-year max drawdown and annualized return. Enforces a minimum Calmar ratio ($>\text{threshold}$) to eliminate tail-risk assets.
  3. **Dynamic Sharpe Ratio Ranking**: Dynamically fetches the 8-hour perpetual funding rate to calculate an annualized risk-free rate ($R_f = \text{fundingRate} \times 3 \times 365$). Ranks candidate assets by risk-adjusted Sharpe ratio using local volatility estimates.

* **Tri-Factor Ensemble Forecast Engine**:
  * **$W_k$ (Kinematic Momentum Drift)**: Rolling return velocity and kurtosis expansion detector ($K > 4.50$ cutoff).
  * **$W_p$ (Order-Book Liquidity Pain Path)**: Order Flow Imbalance (OFI) and bid/ask microstructural slippage traversal using live Binance Level-2 depth (`@depth20@250ms`).
  * **$W_\alpha$ (FRVP 50-Bin & VWAP Confluence)**: Fixed-Range Volume Profile (POC, 70% Value Area VAH/VAL) with an 80% Auction Market Value Area Rotation Rule.
  * **Online Model Adaptation**: Dynamically adjusts factor weights ($W_k, W_p, W_\alpha$) in real time using online Stochastic Gradient Descent (SGD) and RMSE inverse-variance calibration on settled predictions.

* **Strict Up-To 5-Decimal Precision Standard**:
  * All coin prices across the entire workstation adhere to a strict 5-decimal places standard (e.g. `"$64520.12345"`, `"$0.07731"`).
  * Applied consistently to spot prices, high/low ranges, target forecasts, trajectory chart axes, prediction audit ledgers, CSV exports, and background notifications.

* **24/7 Persistent Background Workstation**:
  * Built with an Android Foreground Service (`AlphaPulseService`) backed by `PowerManager.PARTIAL_WAKE_LOCK`.
  * Displays an ongoing system notification with live spot price, countdown timer to the next target boundary, and Markov regime state.

---

## 📜 Changelog of Implemented Features

### 🟢 Version 14.3 (Current Release)
- **Strict 5-Decimal Price Precision Standard**:
  - Implemented centralized `Formatters.formatPrice5Dec()` and `Formatters.formatRawPrice5Dec()`.
  - Applied formatting across all Composables, Canvas charts, audit ledgers, drawer notifications, and CSV generators.
- **High-Frequency WebSocket Engine**:
  - Integrated `BinanceWebSocketManager` streaming `@depth20@250ms` and `@aggTrade` circular buffer (500 trades) for real-time OFI and 95th-percentile empirical shock calculations.
- **Resilient Multi-Endpoint REST Fallbacks**:
  - Updated `ResilientBinanceClient` with strict path-based routing: `/api/` paths query spot mirrors (`data-api.binance.vision`, `api.binance.com`, `api1`-`api3`), and `/fapi/v1/` paths fallback to spot mirrors if futures REST endpoints are geo-restricted.
  - Disabled HTTP redirect following to prevent HTML block pages from corrupting JSON payloads.
- **Automated 4-Tab Universe Audit CSV Export**:
  - Auto-saves `AlphaPulse_Filtered_Universe.csv` upon screening completion:
    - **Tab 1**: Initial Active Futures Universe
    - **Tab 2**: Institutional 4-Filter Passing Assets
    - **Tab 3**: 1-Year Calmar Filtered Assets
    - **Tab 4**: Final Sharpe-Ranked Institutional Universe
  - Integrated Android `FileProvider` for sharing audit spreadsheets.
- **Phase-Locked NTP Candle Boundary Timestamps**:
  - Forecast target timestamps and countdowns are phase-locked to round minute boundaries (`:00.000s`).
- **On-Device Needle Quant Cognitive Agent**:
  - Implemented `QuantNarrativeAgent.kt` to synthesize live kurtosis, L2 order book skew, VWAP distance, Value Area placement, and $1\sigma$ cone tracking precision into real-time executive briefs.
- **Offline Room SQLite Persistence**:
  - Integrated Room database pre-loading so the application renders cached screened coins instantly on startup.
- **GitHub Actions CI/CD Pipeline**:
  - Created `.github/workflows/build-apk.yml` for automated unit testing (`:app:testDebugUnitTest`), debug/release APK assembly (`:app:assembleDebug`, `:app:assembleRelease`), keystore provisioning, and artifact uploads.
- **On-Device TSFM Inference (ONNX Runtime)**:
  - Mounted Chronos-Bolt and TimesFM artifacts now execute on-device through ONNX Runtime; each mounted model contributes its own median forecast and quantile bands to the ensemble instead of a drift term synthesised from market-state heuristics.
  - Sessions open when a model is mounted and close when it is unmounted; a model whose artifact is missing or fails to load is omitted from the ensemble rather than replaced by a synthetic signal.
  - The app ships one `arm64-v8a` APK (no `armeabi-v7a`, `x86`, `x86_64` or universal APK) because the native inference and Cactus engine libraries are large per architecture. The debug and release APKs are uploaded as two separate CI artifacts, and this ABI restriction is pinned by a test.
- **Auditable Decision Ledger**:
  - Every evaluation now records its §29 verdict — state, calibrated confidence, which router answered (Needle 3 or the deterministic rules) and the router's own reason — in the Room database, pruned to the newest 10,000 verdicts per symbol.
  - The prediction audit exports (CSV, JSON, line-per-record) attribute each row to the verdict recorded in its cycle; an uncalibrated verdict reads `UNSCORED` instead of a misleading `0.00`, and a cycle with no recorded verdict leaves the decision columns empty rather than borrowing another symbol's route.
  - The ledger shows the newest five verdicts for the selected asset — time, state, confidence, answering router and reason — read from the same table the export joins on, so the log on screen and a CSV row always agree.
- **Finalized-Contract Guard**:
  - [`FINALIZED_CONTRACTS.md`](./FINALIZED_CONTRACTS.md) records sixteen finalized decisions with the guard spec that proves each one; `FinalizedContractGuardBddSpec` fails the build if a contract is deleted, a guard spec disappears, the pinned checkpoint drifts, a removed remote router returns, or the documented CI gates stop running.
  - `scripts/check_integrity.py` repeats the registry cross-check, so CI and the pre-commit hook catch drift even without Gradle.

---

## 🔮 Roadmap / What's Yet to be Implemented

While AlphaPulse is a complete quantitative screener and forecast workstation, the following institutional capabilities are planned for future engineering iterations:

1. **Direct Exchange API Order Execution & Smart Order Router (SOR)**:
   - Automated order execution (Limit / Post-Only / TWAP / VWAP execution algorithms) via Binance Futures HMAC-SHA256 authenticated API keys.
   - Low-latency Smart Order Routing across multiple USD-M futures contracts.

2. **Coinglass / SmartMoney Paid API Liquidation Heatmap Integration**:
   - Direct API integration with paid liquidation heatmap providers to replace or enrich the current on-device Level-2 Order Book Proxy (`L2 Proxy`).

3. **Multi-Exchange Arbitrage & Funding Rate Scanner**:
   - Cross-exchange perpetual funding rate arbitrage scanner across Binance, Bybit, OKX, and Deribit.
   - Basis spread monitoring between spot and perpetual futures contracts.

4. **Hardware Security Module (HSM) / Android Keystore Key Vault**:
   - Hardware-backed encrypted key storage utilizing Android `KeyGenParameterSpec` and biometric authentication for API secret keys.

5. **Custom Webhook & Telegram Notification Engine**:
   - Configurable push alerts for $1\sigma$ prediction cone breaches, Value Area 80% rotation triggers, and kurtosis volatility bursts delivered via custom Webhooks or Telegram Bot API.

6. **Portfolio Monte Carlo Risk & Value at Risk (VaR) Engine**:
   - Historical simulation and Monte Carlo parametric VaR (95% / 99% confidence level) calculation for multi-asset crypto portfolios.

---

## 🛠️ Build & Development Setup

### Prerequisites
* Java 21 JDK (`temurin` recommended)
* Android SDK 36 (minSdk 24)
* Gradle 8.x (via `./gradlew`)

### First-time setup (once per clone)
```bash
# Install the pre-commit guard that blocks corrupted commits
# (duplicate declarations, conflict markers, stray backups)
git config core.hooksPath .githooks
```

### Change discipline (BDD + CI/CD)

Every change — feature, bug fix or refactor — is made in this order, and no one has
to be reminded of it:

1. **BDD first:** add or extend a `*BddSpec.kt` scenario written as
   *given … when … then …* that fails for the new requirement (or reproduces the bug).
2. **Implement minimally** until it passes. If a finalized decision changes, update
   [`FINALIZED_CONTRACTS.md`](./FINALIZED_CONTRACTS.md) and its guard scenario in the
   same commit — `build/FinalizedContractGuardBddSpec.kt` fails the build when a
   contract row, a guard spec or a pinned value drifts.
3. **Verify:** `./gradlew :app:testDebugUnitTest`, `python3 scripts/check_integrity.py`,
   `./gradlew :app:assembleDebug`, and `./gradlew :app:verifyPackagedWeights` when the
   weights path is involved.
4. **Ship:** CI runs integrity → tests (API 33 & 34) → APK assembly with the
   packaged-weights verification → the published model-artifact contract, then
   publishes a GitHub Release from the verified artifacts; a `v*` tag publishes a
   versioned release.

### Local Commands
```bash
# Run unit & Robolectric tests
./gradlew :app:testDebugUnitTest

# Check the repository is not corrupted (also runs in CI and before each commit)
python3 scripts/check_integrity.py

# Measure coverage and enforce the recorded floor (fails the run on regression)
./gradlew :app:jacocoTestReport :app:jacocoCoverageGate

# User-level tests - these need a connected device or emulator
./gradlew :app:connectedDebugAndroidTest

# Execute the real published ONNX graphs (downloads the pinned artifacts)
VERIFY_ARTIFACT_NETWORK=true ./gradlew :app:testDebugUnitTest \
  --tests "com.aistudio.alphapulse.domain.ml.onnx.OnnxModelParityTest"

# Assemble Debug APK (the arm64-v8a APK under app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# Assemble Release APK (requires STORE_PASSWORD and KEY_PASSWORD)
./gradlew :app:assembleRelease
```

---

## 📄 License & Project Identity
* **App Name**: AlphaPulse
* **Package / Application ID**: `com.aistudio.alphapulse.kxmpzq`
* **Version**: Pro Quant v14.4
