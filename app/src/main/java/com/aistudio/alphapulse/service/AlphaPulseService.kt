package com.aistudio.alphapulse.service

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
    // Veto state fields
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

        private val _serviceTelemetry = MutableStateFlow(ServiceTelemetryUpdate())
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
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var notificationManager: NotificationManager? = null

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

    /** Builds the notification, applying veto logic first */
    private fun buildNotification(state: ServiceTelemetryUpdate): Notification {
        // Apply veto logic - may return null if notification should be suppressed
        val (shouldVeto, vetoReason) = NotificationVeto.checkVeto(
            state.lobUncertainty,
            state.modelDisagreement,
            state.lastDataTimestamp,
            state.requiredModelAvailable
        )

        // If vetoed, log the reason and return a no-op notification or null
        if (shouldVeto) {
            // Log the veto reason for diagnostics
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
            // Return a minimal notification that won't be shown, or return null
            // We return a ongoing notification with low priority that effectively
            // suppresses the alert while keeping the service running
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

        // Normal notification build (no veto)
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

    private fun observeTelemetryUpdates() {
        serviceScope.launch {
            _serviceTelemetry.collect { telemetry ->
                val notification = buildNotification(telemetry)
                notificationManager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY to automatically restart if Android OS terminates the process
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}