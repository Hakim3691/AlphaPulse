package com.aistudio.alphapulse.engine

/**
 * Listen-key lifecycle for the venue user-data stream, as a pure state machine.
 *
 * Binance expires a listen key unless it is kept alive inside its window, and a socket
 * drop leaves the key worthless for the reopened socket. Both cases must lead to a new
 * key, and a key that cannot be re-created must fall back to the on-demand REST
 * reconcile instead of leaving fills unobserved (§25).
 */
class ListenKeySession(private val refreshEveryMs: Long = DEFAULT_REFRESH_MS) {

    enum class State { ACTIVE, NEEDS_RECREATE, REST_FALLBACK }

    enum class Action { NONE, KEEP_ALIVE, RECREATE, REST_RESYNC }

    var state: State = State.NEEDS_RECREATE
        private set

    var key: String? = null
        private set

    private var lastKeepAliveMs: Long = 0L

    fun onStarted(listenKey: String, nowMs: Long) {
        key = listenKey
        lastKeepAliveMs = nowMs
        state = State.ACTIVE
    }

    fun onKeepAliveResult(ok: Boolean, nowMs: Long) {
        if (ok) {
            lastKeepAliveMs = nowMs
            state = State.ACTIVE
        } else {
            state = State.NEEDS_RECREATE
        }
    }

    /** The venue rejected the key itself (-1125), so keeping it alive is pointless. */
    fun onKeyExpired() {
        state = State.NEEDS_RECREATE
    }

    fun onStreamDropped() {
        state = State.NEEDS_RECREATE
    }

    /** No key could be obtained: reconcile must fall back to the REST snapshot. */
    fun onRecreateFailed() {
        key = null
        state = State.REST_FALLBACK
    }

    fun nextAction(nowMs: Long): Action = when (state) {
        State.ACTIVE ->
            if (nowMs - lastKeepAliveMs >= refreshEveryMs) Action.KEEP_ALIVE else Action.NONE
        State.NEEDS_RECREATE -> Action.RECREATE
        State.REST_FALLBACK -> Action.REST_RESYNC
    }

    companion object {
        /** Binance expires a listen key after 60 minutes; refresh well inside that. */
        const val DEFAULT_REFRESH_MS = 30 * 60 * 1000L
    }
}
