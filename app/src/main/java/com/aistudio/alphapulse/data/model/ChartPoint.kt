package com.aistudio.alphapulse.data.model

/** Market candlestick/kline point with all fields needed for TSFM prediction input and UI charting. */
data class ChartPoint(
    /** Timestamp of the candle */
    val timestamp: Long,
    /** Timestamp of the candle (formatted) */
    val timeLabel: String,
    /** Open price */
    val open: Double,
    /** High price */
    val high: Double,
    /** Low price */
    val low: Double,
    /** Close price */
    val close: Double,
    /** Volume */
    val volume: Double,
    /** Realized volatility over lookback window */
    val realizedVolatility: Double,
    /** Funding rate */
    val funding: Double,
    /** Open interest */
    val openInterest: Double,
    /** OI change */
    val oiChange: Double,
    /** Taker buy ratio */
    val takerBuyRatio: Double,
    /** VWAP distance from close */
    val vwapDistance: Double,
    /** POC (Point of Control) distance */
    val pocDistance: Double,
    /** VAH (Value Area High) distance */
    val vahDistance: Double,
    /** VAL (Value Area Low) distance */
    val valDistance: Double,
    /** L2 order book imbalance */
    val l2Imbalance: Double,
    /** Spread */
    val spread: Double,
    /** Depth velocity */
    val depthVelocity: Double,
    /** Liquidation intensity */
    val liquidationIntensity: Double,
    /** Upper liquidity distance */
    val upperLiquidityDistance: Double,
    /** Lower liquidity distance */
    val lowerLiquidityDistance: Double,
    /** Market regime */
    val regime: String,
    /** Prediction horizon */
    val horizon: String? = null,
    /** Additional metadata */
    val metadata: Map<String, Any> = emptyMap()
) {
    /** Alias for close price (realized price at candle close) */
    val realizedPrice: Double get() = close
    /** Alias for close price (forecast/expected price) */
    val forecastPrice: Double get() = close
    /** Upper cone / resistance level (using high) */
    val upperCone: Double get() = high
    /** Lower cone / support level (using low) */
    val lowerCone: Double get() = low
}