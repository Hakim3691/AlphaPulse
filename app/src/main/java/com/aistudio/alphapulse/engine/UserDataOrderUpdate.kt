package com.aistudio.alphapulse.engine

import org.json.JSONObject

/** The order statuses that change what the ladder should believe rests at the venue. */
enum class OrderUpdateStatus {
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    EXPIRED,
    REJECTED
}

/** One parsed ORDER_TRADE_UPDATE, reduced to what [LiveOrderCoordinator.reconcile] needs. */
data class OrderUpdate(
    val symbol: String,
    val clientOrderId: String,
    val status: OrderUpdateStatus,
    val executedQty: Double,
    val orderQty: Double
) {
    /** Every parsed update describes a decided order, so it is always worth reconciling. */
    val asksForReconcile: Boolean get() = true

    /** Full execution only: a partial fill is exposure, not a filled slice (§22, §25). */
    val isFullyExecuted: Boolean
        get() = orderQty > 0.0 && executedQty >= orderQty - 1e-9
}

/**
 * Maps venue user-data frames onto reconcile triggers.
 *
 * The stream is a *trigger*, never a second book: an actionable update is answered by the
 * same REST reconcile the resync action uses, so ladder semantics stay in one place.
 * Anything this mapper does not understand — an unknown event type, an unknown order
 * status, a missing client order id, an unparseable frame — is ignored rather than
 * booked as a fill.
 */
object UserDataOrderUpdate {

    /** Added by Binance when the listen key is gone; the stream must be re-created. */
    private const val LISTEN_KEY_INVALID = -1125

    fun parse(frame: String): OrderUpdate? {
        val root = runCatching { JSONObject(frame) }.getOrNull() ?: return null
        if (root.optString("e") != "ORDER_TRADE_UPDATE") return null
        val order = root.optJSONObject("o") ?: return null
        val symbol = order.optString("s", "").ifBlank { return null }
        val clientOrderId = order.optString("c", "").ifBlank { return null }
        val status = statusOf(order.optString("X", "")) ?: return null
        val orderQty = order.optDoubleOrNull("q") ?: return null
        val executedQty = order.optDoubleOrNull("z") ?: 0.0
        if (orderQty <= 0.0 || executedQty < 0.0) return null
        return OrderUpdate(symbol, clientOrderId, status, executedQty, orderQty)
    }

    /** True for an error frame saying the listen key expired or no longer exists. */
    fun isListenKeyExpired(frame: String): Boolean {
        val error = runCatching { JSONObject(frame).optJSONObject("error") }.getOrNull() ?: return false
        if (error.optInt("code", 0) == LISTEN_KEY_INVALID) return true
        val message = error.optString("msg", "").lowercase()
        return "listenkey" in message && "not exist" in message
    }

    private fun statusOf(raw: String): OrderUpdateStatus? = when (raw) {
        "PARTIALLY_FILLED" -> OrderUpdateStatus.PARTIALLY_FILLED
        "FILLED" -> OrderUpdateStatus.FILLED
        "CANCELED" -> OrderUpdateStatus.CANCELED
        "EXPIRED" -> OrderUpdateStatus.EXPIRED
        "REJECTED" -> OrderUpdateStatus.REJECTED
        // NEW and anything else changes nothing the ladder should believe.
        else -> null
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key)) return null
        return when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
}
