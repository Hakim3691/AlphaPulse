package com.aistudio.alphapulse.service

/** 
 * The long-running background service.
 * 
 * Keeps the quantitative pipeline alive 24/7 with a foreground notification,
 * so forecasts and alerts continue while the app is closed.
 * 
 * Extended live Binance order staging and cancellation (Phase 4 Spec §22, §25, §33):
 * - Subscribes to Binance combined-market WS streams for real price data
 * - Generates campaign plans via CampaignEngine
 * - Gates campaigns through ExecutionEngine before order routing
 * - Routes approved campaigns via BinanceSignedClient (place/amend/cancel orders)
 * - Maintains listen key for persistent user-data stream
 * - Reconciles fills and cancels from exchange events
 * 
 * Spec Ref: §24 Persistent Background Engine, §22 Campaign Staging, §25 Order Execution,
 * §33 Venue Health & Session Guardrails
 */

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aistudio.alphapulse.MainActivity
import com.aistudio.alphapulse.core.Formatters
import com.aistudio.alphapulse.service.NotificationVeto
import com.aistudio.alphapulse.data.remote.BinanceFuturesClient
import com.aistudio.alphapulse.data.remote.BinanceSignedClient
import com.aistudio.alphapulse.data.remote.BinanceWebSocketClient
import com.aistudio.alphapulse.engine.CampaignEngine
import com.aistudio.alphapulse.engine.ExecutionEngine
import com.aistudio.alphapulse.engine.BinanceSignedOrderPort
import com.aistudio.alphapulse.engine.LiveOrderCoordinator
import com.aistudio.alphapulse.engine.OrderStager
import com.aistudio.alphapulse.engine.RiskEngine
import com.aistudio.alphapulse.data.model.Campaign
import com.aistudio.alphapulse.data.model.CampaignState
import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.OrderType
import com.aistudio.alphapulse.data.model.RiskEnvelope
import com.aistudio.alphapulse.data.model.VenueHealth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class ServiceTelemetryUpdate(
    val symbol: String = "BTCUSDT",
    val spotPrice: Double = 0.0,
    val secondsToTarget: Int = 60,
    val regime: String = "Evaluating...",
    val isRunning: Boolean = true,
    val lobUncertainty: Float = 0.0f,
    val modelDisagreement: Float = 0.0f,
    val lastDataTimestamp: Long = 0,
    val requiredModelAvailable: Boolean = true
)

class AlphaPulseService : Service() {

    companion object {
        const val CHANNEL_ID = "alphapulse_live_telemetry"
        const val NOTIFICATION_ID = 1001
        private const val WAKE_LOCK_TAG = "AlphaPulse::ServiceWakeLock"
        private const val LISTEN_KEY_REFRESH_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

        // Live trading configuration
        const val MAX_NOTIONAL_PER_CAMPAIGN_USD = 100.0
        const val DEFAULT_RISK_BUDGET_USD = 1000.0

        val _serviceTelemetry = MutableStateFlow(ServiceTelemetryUpdate())
        val serviceTelemetry: StateFlow<ServiceTelemetryUpdate> = _serviceTelemetry.asStateFlow()

        fun updateTelemetry(
            symbol: String,
            spotPrice: Double,
            secondsToTarget: Int,
            regime: String,
            lobUncertainty: Float = 0.0f,
            modelDisagreement: Float = 0.0f,
            lastDataTimestamp: Long = 0,
            requiredModelAvailable: Boolean = true
        ) {
            _serviceTelemetry.value = ServiceTelemetryUpdate(
                symbol = symbol,
                spotPrice = spotPrice,
                secondsToTarget = secondsToTarget,
                regime = regime,
                isRunning = true,
                lobUncertainty = lobUncertainty,
                modelDisagreement = modelDisagreement,
                lastDataTimestamp = lastDataTimestamp,
                requiredModelAvailable = requiredModelAvailable
            )
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val supervisorJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + supervisorJob)
    
    private var notificationManager: NotificationManager? = null
    private var binanceWebSocketJob: Job? = null
    private var listenKey: String? = null
    private var lastListenKeyRefresh = java.lang.System.currentTimeMillis()
    private val stagedClientOrderIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Acquire partial wake lock for non-stop 24/7 background computation
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24-hour safety timeout
        }

        startForegroundWithNotification()
        observeTelemetryUpdates()

        // Initialize live Binance trading pipeline
        initLiveTradingPipeline()
    }

    private fun initLiveTradingPipeline() {
        // Real book-ticker stream for the staged symbol; the cancellation engine reads
        // the same prices the orders were staged against.
        binanceWebSocketJob = serviceScope.launch {
            BinanceWebSocketClient.streamBookTicker("BTCUSDT").collect { delta ->
                updateTelemetry(
                    symbol = "BTCUSDT",
                    spotPrice = delta.midPrice,
                    secondsToTarget = 60,
                    regime = "Live",
                    lobUncertainty = 0.0f,
                    modelDisagreement = 0.0f,
                    lastDataTimestamp = System.currentTimeMillis(),
                    requiredModelAvailable = true
                )
            }
        }

        // Start listen key lifecycle management
        serviceScope.launch {
            manageListenKey()
        }
    }

    private suspend fun manageListenKey() {
        // Create initial listen key
        val apiKey = System.getenv("ALPHAPULSE_API_KEY") ?: ""
        val secret = System.getenv("ALPHAPULSE_API_SECRET")?.toCharArray() ?: CharArray(0)

        if (apiKey.isNotBlank() && secret.isNotEmpty()) {
            val keyResult = BinanceSignedClient.createListenKey(apiKey.toCharArray())
            keyResult?.let {
                listenKey = it
                Log.d("AlphaPulseService", "Listen key established: ${listenKey?.substring(0, 8) ?: "unknown"}...")
            } ?: run {
                Log.w("AlphaPulseService", "Failed to create Binance listen key")
            }
        }

        // Periodic refresh
        serviceScope.launch {
            while (true) {
                delay(LISTEN_KEY_REFRESH_INTERVAL_MS)
                if (listenKey != null && apiKey.isNotBlank() && secret.isNotEmpty()) {
                    BinanceSignedClient.keepAliveListenKey(apiKey.toCharArray())
                }
            }
        }
    }

    /** 
     * Process a campaign through the execution gate and route orders to Binance.
     * Called when new campaign plans are generated.
     */
    suspend fun processCampaign(campaign: Campaign, mode: ExecutionMode) {
        val executionEngine = ExecutionEngine(RiskEngine())
        val gatedCampaigns = executionEngine.gateStaging(
            listOf(campaign),
            mode,
            RiskEnvelope(cappedNotionalPerCampaignUsd = MAX_NOTIONAL_PER_CAMPAIGN_USD),
            VenueHealth(),
            hasActiveSession = listenKey != null
        )

        val gated = gatedCampaigns.first()
        when {
            gated.note?.startsWith("Shadow") == true -> {
                // Shadow mode: just emit alerts, no orders routed
                Log.i("AlphaPulseService", "Campaign in shadow mode: ${gated.note}")
            }
            gated.note?.startsWith("Paper") == true -> {
                // Paper mode: internal fill simulation, no exchange calls
                Log.i("AlphaPulseService", "Campaign in paper mode: ${gated.note}")
                // Simulate paper fills against live price
                val lastPrice = _serviceTelemetry.value.spotPrice
                if (lastPrice > 0.0) {
                    // Use ExecutionEngine's paper fill simulation
                    // Note: In full implementation, would call engine.simulatePaperFills()
                }
            }
            else -> {
                // Live modes: route orders via Binance signed client
                routeLiveOrders(gated.campaign)
            }
        }
    }

    /** §22: stage this campaign through the guardrails, then submit what survives. */
    private suspend fun routeLiveOrders(campaign: Campaign) {
        val apiKey = System.getenv("ALPHAPULSE_API_KEY") ?: ""
        val secret = System.getenv("ALPHAPULSE_API_SECRET")?.toCharArray() ?: CharArray(0)
        if (apiKey.isBlank() || secret.isEmpty()) {
            Log.w("AlphaPulseService", "Binance API credentials not configured; cannot place live orders")
            return
        }

        val plan = OrderStager(RiskEngine()).plan(
            campaigns = listOf(campaign),
            mode = ExecutionMode.SCALED_LIVE,
            envelope = RiskEnvelope(cappedNotionalPerCampaignUsd = MAX_NOTIONAL_PER_CAMPAIGN_USD),
            venueHealth = VenueHealth(),
            hasActiveSession = listenKey != null,
            alreadyStaged = stagedClientOrderIds.toSet()
        )
        plan.rejections.forEach { rejection ->
            Log.w("AlphaPulseService", "Guardrail blocked ${rejection.clientOrderId}: ${rejection.reason}")
        }
        if (plan.intents.isEmpty()) return

        val submissions = LiveOrderCoordinator(BinanceSignedOrderPort)
            .submit(plan, apiKey.toCharArray(), secret)
        submissions.forEach { submission ->
            val intent = submission.intent
            if (submission.ok) {
                stagedClientOrderIds += intent.clientOrderId
                Log.d(
                    "AlphaPulseService",
                    "Placed ${intent.clientOrderId} ${intent.side} ${intent.qty} @ ${intent.price}"
                )
            } else {
                Log.w("AlphaPulseService", "Rejected ${intent.clientOrderId}: ${submission.message}")
            }
        }
    }

    /** §27: cancel one staged order, treating a filled order as a fill, not a failure. */
    suspend fun cancelStagedOrder(symbol: String, clientOrderId: String): LiveOrderCoordinator.CancelOutcome? {
        val apiKey = System.getenv("ALPHAPULSE_API_KEY") ?: return null
        val secret = System.getenv("ALPHAPULSE_API_SECRET")?.toCharArray() ?: return null
        if (apiKey.isBlank() || secret.isEmpty()) return null
        val outcome = LiveOrderCoordinator(BinanceSignedOrderPort)
            .cancelOne(symbol, clientOrderId, apiKey.toCharArray(), secret)
        if (outcome !is LiveOrderCoordinator.CancelOutcome.Failed) stagedClientOrderIds -= clientOrderId
        return outcome
    }

    /** §27: cancel every order the venue holds for a symbol. */
    suspend fun cancelAllStagedOrders(symbol: String): Boolean {
        val apiKey = System.getenv("ALPHAPULSE_API_KEY") ?: return false
        val secret = System.getenv("ALPHAPULSE_API_SECRET")?.toCharArray() ?: return false
        if (apiKey.isBlank() || secret.isEmpty()) return false
        val ok = LiveOrderCoordinator(BinanceSignedOrderPort)
            .cancelSymbol(symbol, apiKey.toCharArray(), secret)
        if (ok) stagedClientOrderIds.clear()
        return ok
    }

    /** §25: recover order state from REST after a listen-key gap. */
    suspend fun reconcileStagedOrders(
        symbol: String,
        campaigns: List<Campaign>
    ): LiveOrderCoordinator.ReconcileResult? {
        val apiKey = System.getenv("ALPHAPULSE_API_KEY") ?: return null
        val secret = System.getenv("ALPHAPULSE_API_SECRET")?.toCharArray() ?: return null
        if (apiKey.isBlank() || secret.isEmpty()) return null
        val result = LiveOrderCoordinator(BinanceSignedOrderPort)
            .reconcile(symbol, campaigns, apiKey.toCharArray(), secret, staged = stagedClientOrderIds.toSet())
        stagedClientOrderIds.clear()
        stagedClientOrderIds.addAll(result.openClientOrderIds)
        return result
    }

    /** Reconcile a fill event from the exchange */
    fun reconcileFill(campaign: Campaign, clientOrderId: String): Campaign {
        return ExecutionEngine(RiskEngine()).reconcileFill(campaign, clientOrderId)
    }

    /** Reconcile a cancel event from the exchange */
    fun reconcileCancel(campaign: Campaign, clientOrderId: String): Campaign {
        return ExecutionEngine(RiskEngine()).reconcileCancel(campaign, clientOrderId)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AlphaPulse Live Telemetry",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing institutional price, regime, and countdown telemetry"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /** Mirrors telemetry into the foreground notification (veto applied per update). */
    private fun observeTelemetryUpdates() {
        serviceScope.launch {
            _serviceTelemetry.collect { telemetry ->
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(telemetry))
            }
        }
    }

    private fun startForegroundWithNotification() {
        val initialNotification = buildNotification(_serviceTelemetry.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun buildNotification(state: ServiceTelemetryUpdate): Notification {
        val (shouldVeto, vetoReason) = NotificationVeto.checkVeto(
            state.lobUncertainty,
            state.modelDisagreement,
            state.lastDataTimestamp,
            state.requiredModelAvailable
        )

        if (shouldVeto) {
            val cleanSym = state.symbol.replace("USDT", "")
            val lobUncStr = String.format(Locale.US, "%.1f", state.lobUncertainty)
            val modelDisagreeStr = String.format(Locale.US, "%.1f", state.modelDisagreement)
            Log.d(
                "AlphaPulseService",
                "Notification VETOED: ${ vetoReason?.orEmpty() }"
                    + " | Symbol: $cleanSym"
                    + " | Lob uncertainty: $lobUncStr"
                    + " | Model disagreement: $modelDisagreeStr"
            )
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AlphaPulse $cleanSym")
                .setContentText("Vetoed: ${vetoReason?.orEmpty()}")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanSym = state.symbol.replace("USDT", " ")
        val priceStr = Formatters.formatPrice5Dec(state.spotPrice)
        val m = (state.secondsToTarget / 60).toString().padStart(2, '0')
        val s = (state.secondsToTarget % 60).toString().padStart(2, '0')
        val countdownStr = "$m:$s"

        val title = "AlphaPulse $priceStr $cleanSym"
        val content = "Target: In $countdownStr | ${state.regime}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.d("AlphaPulseService", "Error releasing wake lock: ${e.message}")
        }
        _serviceTelemetry.value = _serviceTelemetry.value.copy(isRunning = false)
        binanceWebSocketJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}