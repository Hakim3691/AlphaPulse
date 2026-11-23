package com.aistudio.alphapulse

/**
 * The app's front door.
 *
 * Creates the main screen, asks for notification permission on Android 13+,
 * and starts the always-on background engine so forecasts keep running when the
 * screen is off.
 *
 * Spec Ref: §39 User Interface, §24 Persistent Background Engine
 */

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.FrameMetrics
import android.view.Window
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphapulse.billing.SimulatedTraderManager
import com.alphapulse.contract.ModuleInstallState
import com.aistudio.alphapulse.data.local.SharedPreferencesPaperTrialEvidenceStore
import com.aistudio.alphapulse.engine.proactive.BaseViewHealthMonitor
import com.aistudio.alphapulse.engine.proactive.ProactiveEvidenceRuntime
import com.aistudio.alphapulse.service.AlphaPulseService
import com.aistudio.alphapulse.ui.AlphaPulseScreen
import com.aistudio.alphapulse.ui.AppShell
import com.aistudio.alphapulse.ui.ProactiveMsTab
import com.aistudio.alphapulse.ui.theme.MyApplicationTheme
import com.aistudio.alphapulse.ui.viewmodel.AlphaPulseViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AlphaPulseViewModel by viewModels()

    /**
     * §38 base-view evidence: rendered frame durations of the analysis screen. The
     * observation is recorded into the same process tracker the staging gate reads, and
     * it is persisted, so a relaunch cannot silently forget that the base view regressed.
     */
    private val baseViewHealth = BaseViewHealthMonitor()
    private val frameMetricsHandler by lazy { Handler(Looper.getMainLooper()) }
    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val durationMs = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
        val observation = baseViewHealth.recordFrame(durationMs)
            ?: return@OnFrameMetricsAvailableListener
        ProactiveEvidenceRuntime.runner(
            SharedPreferencesPaperTrialEvidenceStore(applicationContext)
        ).recordBaseViewObservation(observation.regressionDetected)
    }

    /**
     * Phase 1 D5: the offline Play Billing / SplitInstall emulation.
     *
     * One manager per activity, so the entitlement it restores is the same one the
     * shell renders and the tab injection reads.
     */
    private val traderManager by lazy { SimulatedTraderManager(applicationContext) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startAlphaPulseService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startAlphaPulseService()
            }
        } else {
            startAlphaPulseService()
        }

        setContent {
            MyApplicationTheme {
                val installState by traderManager.installState.collectAsStateWithLifecycle()
                val downloadProgress by traderManager.downloadProgress.collectAsStateWithLifecycle()

                AppShell(
                    installState = installState,
                    downloadProgress = downloadProgress,
                    onUnlockTrader = {
                        if (installState == ModuleInstallState.NOT_INSTALLED ||
                            installState == ModuleInstallState.ENTITLEMENT_REQUIRED
                        ) {
                            // The simulated Play sheet persists the entitlement and
                            // starts the download progression.
                            traderManager.requestPurchaseSimulation(this@MainActivity) { }
                        } else {
                            traderManager.startDynamicDownloadSimulation()
                        }
                    },
                    onTraderTab = {
                        // A WAITING tab is inert: launching before INSTALLED is a
                        // no-op, never a crash or a half-installed dashboard.
                        traderManager.launchTraderDashboard(this@MainActivity)
                    },
                    onSelectTab = { tab ->
                        // C-57: the shared bar launches the requested tab. AlphaPulse
                        // is this screen already, so only a workspace tab navigates.
                        if (tab != ProactiveMsTab.ALPHA_PULSE) {
                            traderManager.launchTraderTab(this@MainActivity, tab.name)
                        }
                    }
                ) {
                    AlphaPulseScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, frameMetricsHandler)
    }

    override fun onPause() {
        window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        super.onPause()
    }

    private fun startAlphaPulseService() {
        try {
            val serviceIntent = Intent(this, AlphaPulseService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

