# AlphaPulse Android
### Native Institutional Quantitative Cryptocurrency Prediction Workstation

**Product**: AlphaPulse Android  
**Baseline**: AlphaPulse PRO QUANT V14.3 TRI-FACTOR HTML5 SPA  
**Target**: Native Android  
**Primary function**: Institutional cryptocurrency futures screening, multi-horizon probabilistic forecasting, adaptive ensemble learning and on-device quantitative intelligence  
**Forecast horizons**: 1m, 5m, 15m, 30m, 1h  
**Primary market**: Crypto perpetual futures  
**Initial exchange**: Binance USDⓈ-M Futures  
**Secondary exchanges**: Bybit, Hyperliquid  
**Architecture**: Kotlin + Jetpack Compose + Coroutines + Room + WebSockets + Native ML Inference  

---

## 1. Product Vision

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

## 2. Core Design Principle

The HTML5 Tri-Factor model shall remain intact as the baseline.

The Android architecture shall extend it to:

```
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
```

---

## 3. Functional Requirements

### FR-001: Institutional Universe Scanner

The Android application shall scan the Binance USDⓈ-M Futures universe automatically.

The scanner shall retain the existing three-stage pipeline.

#### Stage 1: Institutional 4-Filter Gate

A contract shall qualify when all four conditions are satisfied either:
1. today, or
2. simultaneously on at least one day during the previous 10 days.

The four conditions remain:
* **Filter 1: Institutional Volume**: 24h Quote Volume $\ge \$5,000,000\text{ USDT}$
* **Filter 2: Aggressive Taker Buying**: Taker Buy Ratio $\ge 50\%$
* **Filter 3: Volume/OI Velocity**: 24h Quote Volume / Open Interest $> 1.0$
* **Filter 4: Conviction Displacement**: $|\text{Close} - \text{Open}| / (\text{High} - \text{Low}) \ge 40\%$

---

## 4. Risk-Adjusted Universe Ranking

### FR-002: Calmar Filter
The application shall calculate:
- total return;
- annualized return;
- maximum drawdown;
- Calmar ratio.

Only $\text{Calmar} > 0$ shall proceed.

### FR-003: Sharpe Ranking
The application shall calculate:
$$\text{Sharpe} = \frac{\text{Annualized Return} - R_f}{\text{Annualized Volatility}}$$

The HTML5 baseline uses $R_f = 4\%$. The Android implementation shall support a dynamic risk-free/opportunity-cost source, subject to data availability. The original 4% value shall remain available as a fallback.

---

## 5. Native Networking Layer

### FR-004: Exchange Connectivity
The application shall use native HTTP/WebSocket infrastructure rather than browser APIs. Recommended stack:
- Kotlin;
- OkHttp;
- Retrofit where appropriate;
- Kotlin Coroutines;
- Flow/StateFlow;
- HTTP/2 connection pooling;
- WebSocket reconnection manager.

### FR-005: Endpoint Resilience
The system shall support endpoint failover across Binance, Bybit, and Hyperliquid adapters (`ExchangeDataProvider`).

---

## 6. Real-Time Market Data Engine

The Android application shall continuously ingest:
- **Price**: trades, aggregate trades, candles, mark price, index price.
- **Derivatives**: open interest, OI change, funding rate, basis, taker buy/sell volume, liquidation events.
- **Order Book**: bid/ask depth, depth deltas, spread, imbalance, depth velocity, replenishment, depletion, liquidity voids.
- **Liquidations**: long liquidations, short liquidations, liquidation clusters, liquidation intensity, liquidation direction.

---

## 7. Full Order Book Reconstruction

### FR-006: Deep L2/L3 Architecture
Reconstructs order books (Top 20, Top 50, Top 100, Top 500, Top 1000) to detect absorption, spoofing, stacking, cancellations, replenishment, and depth collapse.

---

## 8. Horizon-Specific Liquidity Pain Model

Independently calculates timeframe-specific pain-path vectors ($\mu_{p,1\text{m}}, \mu_{p,5\text{m}}, \mu_{p,15\text{m}}, \mu_{p,30\text{m}}, \mu_{p,60\text{m}}$) using liquidity actually reachable within each horizon's volatility reach.

---

## 9. Proprietary Liquidation Heatmap

### FR-007: Multi-Exchange Liquidation Engine
Constructs a local liquidation map calculating attraction score:
$$\text{Attraction}(P) = \frac{\text{LiquidationVolume}(P)}{\text{Distance}(\text{Spot}, P)}$$

---

## 10. Continuous Institutional Trade-Tape Analysis

### FR-008: Rolling Trade Buffer
Maintains a rolling trade buffer in Room DB to calculate true time-horizon-matched institutional block shocks ($\text{InstitutionalShock}(1\text{m}), \dots, \text{InstitutionalShock}(1\text{h})$).

---

## 11. Existing Tri-Factor Engine

Retains Vector 1 (Kinematic Momentum), Vector 2 (Liquidity Pain Path), and Vector 3 (Confluence Alpha Vector) as the baseline quantitative core.

---

## 12. Full Volume Profile

Constructs a true 24-hour Fixed-Range Volume Profile with adaptive Freedman-Diaconis bin width:
$$\text{Bin Width} = 2 \cdot \text{IQR} \cdot n^{-1/3}$$

---

## 13. Neural UQ-LOB & 14. AGA-HMM Upgrade

Provides ONNX Runtime Mobile / ExecuTorch hardware-accelerated inference for neural order-book uncertainty classification and regime state transitions.

---

## 15. New Fourth Forecasting Factor ($W_{\text{tsfm}}, \mu_{\text{tsfm}}$)

Four-factor ensemble formula:
$$\hat{\mu} = (W_k \cdot \mu_k) + (W_p \cdot \mu_p) + (W_\alpha \cdot \mu_\alpha) + (W_{\text{tsfm}} \cdot \mu_{\text{tsfm}}) + \Delta\mu_{\text{bias}}$$
where $W_k + W_p + W_\alpha + W_{\text{tsfm}} = 1.0$.

---

## 16–19. Time-Series Foundation Models (TSFM) & Probabilistic Distribution

Supports pluggable TSFM providers (Kronos-small 24.7M, FinText Chronos-Small 46.2M, Chronos-Bolt 21M/48M, and TimesFM 2.0 500M benchmark) outputting $P_{10}, P_{25}, P_{50}, P_{75}, P_{90}$ quantile distributions.

---

## 20–23. Adaptive Ensemble Learning, Room Database & NTP Phase Locking

Replaces $10^5$-scaled SGD with AdamW / Kalman Filter / Online Mirror Descent optimizer with persistent per-coin and per-horizon weight tables in Room DB. Phase-locks predictions to exact NTP-aligned candle boundaries (`:00.000s`).

---

## 24–27. Persistent Background Foreground Service & Hardware Acceleration

Uses an Android Foreground Service with partial `WakeLock` and `WorkManager` for continuous 24/7 background execution with ExecuTorch / ONNX / Vulkan NPU acceleration.

---

## 28–30. Edge SLM, Decision Router & Needle Agent Orchestration

Employs quantized on-device Small Language Models (Llama-3.2-1B, Gemma-2-2B, Phi-3.5-Mini) for narrative executive briefs, coupled with Microsoft-Decision-1 decision router and Needle Agent DAG orchestration.

---

## 31–64. Full System Specifications & Release Roadmap

Includes complete specifications for model health diagnostics, model disagreement engine, Room DB schema, Compose UI architecture, and release milestones (Release 1: Native Parity -> Release 2: Quant Upgrade -> Release 3: Multi-Model Intelligence -> Release 4: Edge Intelligence).
