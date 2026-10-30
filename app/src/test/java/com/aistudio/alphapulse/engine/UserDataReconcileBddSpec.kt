package com.aistudio.alphapulse.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Venue user-data events feeding the one reconcile path, specified as Given/When/Then.
 *
 * A stream frame is a *trigger*, never a second book: an actionable update is answered by
 * the same [LiveOrderCoordinator.reconcile] the REST resync uses, so the ladder semantics
 * (including `OpenOrder.isFullyExecuted`) hold for streamed fills too.
 *
 * Spec Ref: §22 Order Staging, §25 Monitoring, §27 Invalidation/Cancellation
 */
@RunWith(RobolectricTestRunner::class)
class UserDataReconcileBddSpec {

    private fun frame(
        status: String = "PARTIALLY_FILLED",
        filledQty: String = "0.4",
        orderQty: String = "1.0",
        clientOrderId: String = "BTCUSDT_LONG_1",
        symbol: String = "BTCUSDT"
    ) = """
        {"e":"ORDER_TRADE_UPDATE","E":1700000000000,"T":1700000000001,"o":{
          "s":"$symbol","c":"$clientOrderId","i":11,"S":"BUY","X":"$status",
          "q":"$orderQty","z":"$filledQty","p":"150.0","l":"0.4","L":"150.0","x":"TRADE"}}
    """.trimIndent()

    private val now = 1_700_000_000_000L
    private val minutes: (Long) -> Long = { it * 60_000L }

    // ------------------------------------------------------------ order updates

    @Test
    fun `given a partially filled order update, when mapped, then it asks for a reconcile but is not a full fill`() {
        // Given: the venue reports 40% of a one-unit entry executed.
        val update = UserDataOrderUpdate.parse(frame())

        // Then: the event is actionable, and the partial quantity is not a fill.
        assertNotNull(update)
        assertEquals("BTCUSDT", update!!.symbol)
        assertEquals("BTCUSDT_LONG_1", update.clientOrderId)
        assertEquals(OrderUpdateStatus.PARTIALLY_FILLED, update.status)
        assertTrue(update.asksForReconcile)
        assertFalse(update.isFullyExecuted)
    }

    @Test
    fun `given a fully filled order update, when mapped, then it asks for a reconcile and counts as a full fill`() {
        val update = UserDataOrderUpdate.parse(
            frame(status = "FILLED", filledQty = "1.0", orderQty = "1.0")
        )

        assertNotNull(update)
        assertEquals(OrderUpdateStatus.FILLED, update!!.status)
        assertTrue(update.asksForReconcile)
        assertTrue(update.isFullyExecuted)
    }

    @Test
    fun `given a cancelled order update, when mapped, then it asks for a reconcile so the slice stops resting`() {
        val update = UserDataOrderUpdate.parse(frame(status = "CANCELED"))

        assertNotNull(update)
        assertEquals(OrderUpdateStatus.CANCELED, update!!.status)
        assertTrue(update.asksForReconcile)
    }

    @Test
    fun `given an expired order update, when mapped, then it asks for a reconcile exactly like a cancel`() {
        val update = UserDataOrderUpdate.parse(frame(status = "EXPIRED"))

        assertNotNull(update)
        assertEquals(OrderUpdateStatus.EXPIRED, update!!.status)
        assertTrue(update.asksForReconcile)
    }

    @Test
    fun `given an unknown order status, when mapped, then the event is ignored rather than treated as a fill`() {
        // Given: a status this app has no reconcile semantics for.
        val update = UserDataOrderUpdate.parse(frame(status = "NEW"))

        // Then: nothing is booked.
        assertNull(update)
    }

    @Test
    fun `given a malformed frame, when mapped, then it is ignored`() {
        assertNull(UserDataOrderUpdate.parse("not a json frame"))
        assertNull(UserDataOrderUpdate.parse("""{"e":"ORDER_TRADE_UPDATE"}"""))
        assertNull(UserDataOrderUpdate.parse("""{"e":"ORDER_TRADE_UPDATE","o":{}}"""))
    }

    @Test
    fun `given an order update without a client order id, when mapped, then it is ignored`() {
        assertNull(UserDataOrderUpdate.parse(frame(clientOrderId = "")))
    }

    @Test
    fun `given a non order event, when mapped, then it is ignored`() {
        val account = """{"e":"ACCOUNT_UPDATE","E":1700000000000,"a":{}}"""
        assertNull(UserDataOrderUpdate.parse(account))
    }

    @Test
    fun `given a listen key expiry frame, when inspected, then it is recognised as an expiry and not as an order`() {
        val expiry = """{"error":{"code":-1125,"msg":"This listenKey does not exist."}}"""

        assertTrue(UserDataOrderUpdate.isListenKeyExpired(expiry))
        assertFalse(UserDataOrderUpdate.isListenKeyExpired(frame()))
        assertNull(UserDataOrderUpdate.parse(expiry))
    }

    // ------------------------------------------------------------ listen key state

    @Test
    fun `given a fresh listen key, when asked before the refresh window, then no keep-alive is due`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        assertEquals(ListenKeySession.State.ACTIVE, session.state)
        assertEquals(ListenKeySession.Action.NONE, session.nextAction(now + minutes(29)))
    }

    @Test
    fun `given a fresh listen key, when the refresh window elapses, then a keep-alive is due`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        assertEquals(ListenKeySession.Action.KEEP_ALIVE, session.nextAction(now + minutes(30)))
    }

    @Test
    fun `given a successful keep-alive, when asked again, then the window restarts instead of recreating`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        session.onKeepAliveResult(ok = true, nowMs = now + minutes(30))

        assertEquals(ListenKeySession.State.ACTIVE, session.state)
        assertEquals(ListenKeySession.Action.NONE, session.nextAction(now + minutes(55)))
    }

    @Test
    fun `given a refused keep-alive, when asked for the next action, then the key is recreated`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        session.onKeepAliveResult(ok = false, nowMs = now + minutes(30))

        assertEquals(ListenKeySession.State.NEEDS_RECREATE, session.state)
        assertEquals(ListenKeySession.Action.RECREATE, session.nextAction(now + minutes(30)))
    }

    @Test
    fun `given a key-expired rejection from the venue, when asked for the next action, then the key is recreated`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        session.onKeyExpired()

        assertEquals(ListenKeySession.Action.RECREATE, session.nextAction(now + minutes(1)))
    }

    @Test
    fun `given a socket drop, when asked for the next action, then the key is recreated for the reopened stream`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)

        session.onStreamDropped()

        assertEquals(ListenKeySession.Action.RECREATE, session.nextAction(now + minutes(1)))
    }

    @Test
    fun `given a failed re-create, when asked for the next action, then the REST resync is the fallback`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)
        session.onStreamDropped()

        session.onRecreateFailed()

        assertEquals(ListenKeySession.State.REST_FALLBACK, session.state)
        assertEquals(ListenKeySession.Action.REST_RESYNC, session.nextAction(now + minutes(2)))
        assertNull(session.key)
    }

    @Test
    fun `given a re-created key, when the stream restarts, then the session is active again`() {
        val session = ListenKeySession()
        session.onStarted("key-1", now)
        session.onStreamDropped()

        session.onStarted("key-2", now + minutes(1))

        assertEquals(ListenKeySession.State.ACTIVE, session.state)
        assertEquals("key-2", session.key)
        assertEquals(ListenKeySession.Action.NONE, session.nextAction(now + minutes(2)))
    }
}
