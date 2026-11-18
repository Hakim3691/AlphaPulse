package com.aistudio.alphapulse.data.repository

/**
 * Reuses one fetched value per key for [ttlMs].
 *
 * Why this exists
 * ---------------
 * `executeAssetEvaluation` runs every ten seconds. Some of what it downloads only
 * changes once a minute (the 1m klines) or once every few minutes (a 240-minute
 * liquidation heatmap), so asking for it on every cycle multiplied the app's data
 * use by ten to sixty for identical bytes — the device was measured at ~300 MB per
 * hour. A throttle keyed on the symbol keeps the same evaluation cadence and drops
 * the duplicate requests.
 *
 * A failed fetch (null) is never cached: the next cycle retries instead of freezing
 * the feature for a whole TTL after one network error.
 *
 * Spec Ref: §24 Persistent Background Engine, §33 Venue Health & Session Guardrails
 */
class FetchThrottle<T : Any>(val ttlMs: Long) {

    private data class Entry<T>(val key: String, val fetchedAt: Long, val value: T)

    @Volatile
    private var entry: Entry<T>? = null

    /** The value fetched for [key] at [now], or null when it is stale or another key's. */
    fun cached(key: String, now: Long): T? =
        entry?.takeIf { it.key == key && now - it.fetchedAt in 0 until ttlMs }?.value

    /**
     * Returns the cached value for [key] while it is fresh, otherwise [fetch]'s result.
     * A null result from [fetch] is returned but not stored.
     */
    suspend fun value(key: String, now: Long, fetch: suspend () -> T?): T? {
        cached(key, now)?.let { return it }
        val fresh = fetch() ?: return null
        entry = Entry(key, now, fresh)
        return fresh
    }

    /** Forget the cached value (a symbol switch, or a caller that must force a fetch). */
    fun invalidate() {
        entry = null
    }
}
