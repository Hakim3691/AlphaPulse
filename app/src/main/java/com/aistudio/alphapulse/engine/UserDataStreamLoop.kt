package com.aistudio.alphapulse.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.Arrays

/** -1125: the venue invalidated the listen key, so this socket can never recover. */
class ListenKeyExpiredException : Exception("listen key expired (-1125)")

/**
 * The live user-data stream loop (§25), separated from the ViewModel so the whole cycle
 * is provable without a socket, a real clock or Android.
 *
 * One cycle means: obtain a listen key, refresh it well inside the venue's window, feed
 * every actionable frame into the same reconcile the Resync action runs, and — whatever
 * ended the cycle — reconcile from REST before reopening. A refused refresh abandons the
 * socket instead of reusing a key the venue no longer accepts, and a key that cannot be
 * obtained at all falls back to the open-order snapshot with capped exponential backoff.
 *
 * [releaseKey] is deliberately separate: the owner calls it in a non-cancellable
 * `finally`, so teardown releases the venue-side key even when the job is cancelled.
 */
class UserDataStreamLoop(
    private val orders: LiveOrderCoordinator,
    private val stream: (String) -> Flow<String>,
    private val shouldRun: () -> Boolean,
    private val credentials: () -> Pair<CharArray, CharArray>?,
    private val reconcileSymbol: suspend (String) -> Unit,
    private val reconcileAll: suspend () -> Unit,
    private val note: suspend (code: String, message: String) -> Unit = { _, _ -> },
    private val minBackoffMs: Long = DEFAULT_MIN_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val refreshMs: Long = ListenKeySession.DEFAULT_REFRESH_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** The key the open socket holds, so teardown knows there is one to release. */
    var activeListenKey: String? = null
        private set

    suspend fun run() {
        var backoffMs = minBackoffMs
        while (currentCoroutineContext().isActive && shouldRun()) {
            val creds = credentials() ?: return
            val session = ListenKeySession(refreshMs)
            val connected = try {
                connect(session, creds)
            } finally {
                wipe(creds)
            }
            // §25: a gap or an unavailable key may have missed events, so REST — not the
            // stream — is the source of truth before the next attempt.
            reconcileAll()
            delay(backoffMs)
            backoffMs = if (connected) minBackoffMs else nextBackoff(backoffMs)
        }
    }

    /** Releases the venue-side key; safe to call when the stream never opened. */
    suspend fun releaseKey() {
        if (activeListenKey == null) return
        activeListenKey = null
        val creds = credentials() ?: return
        try {
            if (orders.closeListenKey(creds.first)) {
                note("USER_STREAM_CLOSED", "Listen key released at the venue")
            }
        } finally {
            wipe(creds)
        }
    }

    /** @return true when a listen key was obtained and a socket was attempted. */
    private suspend fun connect(session: ListenKeySession, creds: Pair<CharArray, CharArray>): Boolean {
        val listenKey = orders.ensureListenKey(creds.first) ?: run {
            session.onRecreateFailed()
            note(
                "USER_STREAM_FAILED",
                "Listen key request failed; fills reconcile from the open-order snapshot"
            )
            return false
        }
        session.onStarted(listenKey, nowMs())
        activeListenKey = listenKey
        note("USER_STREAM_OPEN", "User-data stream connected for fill reconciliation")

        val refreshRefused = CompletableDeferred<Unit>()
        val keepAlive = CoroutineScope(currentCoroutineContext()).launch {
            while (isActive) {
                delay(refreshMs)
                val ok = orders.keepAlive(creds.first)
                session.onKeepAliveResult(ok, nowMs())
                if (!ok) {
                    note(
                        "USER_STREAM_KEEPALIVE_FAILED",
                        "Listen key refresh refused; re-creating the stream"
                    )
                    refreshRefused.complete(Unit)
                    return@launch
                }
            }
        }
        val frames = CoroutineScope(currentCoroutineContext()).launch {
            try {
                stream(listenKey).collect { frame ->
                    if (UserDataOrderUpdate.isListenKeyExpired(frame)) {
                        // -1125: this socket cannot recover, so ask for a new key.
                        throw ListenKeyExpiredException()
                    }
                    val update = UserDataOrderUpdate.parse(frame) ?: return@collect
                    if (!update.asksForReconcile) return@collect
                    // §25: one reconcile path — exactly what the Resync action runs, so
                    // partial fills keep the isFullyExecuted semantics.
                    reconcileSymbol(update.symbol)
                }
            } catch (expired: ListenKeyExpiredException) {
                session.onKeyExpired()
                note("USER_STREAM_EXPIRED", "Listen key expired (-1125); re-creating")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                session.onStreamDropped()
                note(
                    "USER_STREAM_GAP",
                    "User-data stream dropped: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
        try {
            select<Unit> {
                frames.onJoin { }
                refreshRefused.onAwait {
                    // A refused refresh means this key is dead: close the socket so the
                    // next cycle opens a fresh one instead of idling on a stale key.
                    frames.cancel()
                }
            }
        } finally {
            keepAlive.cancel()
            frames.cancel()
        }
        return true
    }

    private fun nextBackoff(currentMs: Long): Long =
        (currentMs * 2).coerceAtMost(maxBackoffMs)

    private fun wipe(creds: Pair<CharArray, CharArray>) {
        Arrays.fill(creds.first, '\u0000')
        Arrays.fill(creds.second, '\u0000')
    }

    companion object {
        /** §25: reopen quickly, then back off so a dead venue is not hammered. */
        const val DEFAULT_MIN_BACKOFF_MS = 1_000L
        const val DEFAULT_MAX_BACKOFF_MS = 30_000L
    }
}
