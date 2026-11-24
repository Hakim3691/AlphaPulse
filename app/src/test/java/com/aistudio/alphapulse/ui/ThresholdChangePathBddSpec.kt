package com.aistudio.alphapulse.ui

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.aistudio.alphapulse.data.db.AppDatabase as AlphaDatabase
import com.aistudio.alphapulse.data.db.PredictionEntity
import com.aistudio.alphapulse.data.db.ScreenedCoinEntity
import com.aistudio.alphapulse.data.local.SharedPreferencesThresholdStore
import com.aistudio.alphapulse.data.repository.ThresholdAudit
import com.aistudio.alphapulse.domain.campaign.ThresholdConfig
import com.aistudio.alphapulse.domain.campaign.ThresholdField
import com.aistudio.alphapulse.domain.campaign.Thresholds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * C-60/C-61 — the configured thresholds move through the shipped view model, not a copy.
 *
 * The previous feature proved the service, the store and the domain boundaries, but nothing
 * executed the path an operator actually travels: `MainViewModel.adjustThresholds` →
 * `ThresholdConfigService` → the persisted store → the `decision_records` ledger → the
 * evidence rebuild. These scenarios construct the shipped [MainViewModel] (the same graph
 * `TraderHostActivity` mounts), drive the public entry point, and then read back the three
 * places the value has to land. The rebuild is checked by the in-force window stamped on the
 * evidence it republished, which is the only way to prove a consumer read the new
 * configuration rather than the one it cached.
 *
 * Spec Ref: Phase 2 C-60/C-61, C-29, §21, §34
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThresholdChangePathBddSpec {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(
            SharedPreferencesThresholdStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        Thresholds.install(ThresholdConfig.DEFAULT)
    }

    @After
    fun restoreShippedDefaults() {
        Thresholds.install(ThresholdConfig.DEFAULT)
    }

    /**
     * Runs the main looper until [condition] holds. The view model resumes on the main
     * dispatcher after its Room work, so idling the looper and re-checking is exactly how
     * the shipped app drains the same queue.
     */
    private fun await(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        fail("The shipped view model did not reach the expected state within ${timeoutMs}ms")
    }

    private fun ledgerRows() = runBlocking(Dispatchers.IO) {
        AlphaDatabase.getDatabase(application).decisionRecordDao().getAllDecisionRecords()
    }

    /** Twenty settled, correct 5m calls, measured at [issuedAt], for the inspected coin. */
    private fun seedProvenHorizon(symbol: String, issuedAt: Long) {
        runBlocking(Dispatchers.IO) {
            val db = AlphaDatabase.getDatabase(application)
            db.screenedCoinDao().insertAll(listOf(screened(symbol)))
            db.predictionDao().insertAll(
                List(20) { index ->
                    PredictionEntity(
                        id = "$symbol|5m|$index",
                        symbol = symbol,
                        horizonId = "5m",
                        horizonLabel = "5m",
                        modelType = "Tri-Factor",
                        minutes = 5,
                        issuedTimestamp = issuedAt,
                        targetTimestamp = issuedAt + 5 * 60_000L,
                        basePrice = 60_000.0,
                        predictedPrice = 60_100.0,
                        upperBound = 60_200.0,
                        lowerBound = 59_900.0,
                        muK = 0.0,
                        muP = 0.0,
                        muAlpha = 0.0,
                        settled = true,
                        inCone = true
                    )
                }
            )
        }
    }

    private fun screened(symbol: String) = ScreenedCoinEntity(
        symbol = symbol,
        lastPrice = 60_000.0,
        highPrice = 61_000.0,
        lowPrice = 59_000.0,
        openPrice = 60_000.0,
        volume = 120_000_000.0,
        realTakerBuyRatio = 58.0,
        displacement = 0.55,
        exactOI = 40_000_000.0,
        volToOiRatio = 1.4,
        priceChangePercent = 1.2,
        calmarRatio = 1.0,
        sharpeRatio = 0.9,
        annualizedVol = 0.8,
        maxDrawdown = 12.0,
        daysPassedAll = 3,
        passedTodayAll = true,
        passed10DRule = true,
        filter1Passed = true,
        filter1Val = "120M Vol",
        filter2Passed = true,
        filter2Val = "58.0%",
        filter3Passed = true,
        filter3Val = "1.40x",
        filter4Passed = true,
        filter4Val = "55.0% Ext",
        rank = 1
    )

    @Test
    fun `given the shipped window when the operator widens it on the shipped view model then the store the ledger and the rebuilt evidence all move`() {
        val viewModel = MainViewModel(application)
        val symbol = viewModel.uiState.value.selectedAsset?.symbol
        assertNotNull("The offline seed must select a coin for the operator", symbol)
        seedProvenHorizon(symbol!!, System.currentTimeMillis())

        viewModel.adjustThresholds(ThresholdField.IN_FORCE_MINUTES, 60)

        await { viewModel.uiState.value.thresholds.evidenceTtlMinutes == 120 }
        assertEquals(120, Thresholds.current().evidenceTtlMinutes)
        assertEquals(
            120,
            SharedPreferencesThresholdStore(application).load().evidenceTtlMinutes
        )

        val audit = ledgerRows()
            .filter { it.state == ThresholdAudit.STATE && it.reason.contains("IN_FORCE_MINUTES") }
        assertEquals("An accepted change is exactly one decision", 1, audit.size)
        assertEquals(symbol, audit.single().symbol)
        assertTrue("the old window must be recorded: ${audit.single().reason}", audit.single().reason.contains("60 min"))
        assertTrue("the new window must be recorded: ${audit.single().reason}", audit.single().reason.contains("120 min"))

        // The accepted change rebuilt the cached evidence against the new window, and the
        // rebuilt row is judged in force, so the gates are reading the live configuration.
        await {
            viewModel.uiState.value.layeredEvidence?.price?.any { it.windowMinutes == 120 } == true
        }
        val rebuilt = viewModel.uiState.value.layeredEvidence!!.price.single { it.scope == "5m" }
        assertEquals(120, rebuilt.windowMinutes)
        assertTrue("The rebuilt evidence is fresh and proven: ${rebuilt.staleReason}", rebuilt.qualified)
    }

    @Test
    fun `given a widened window persisted before launch when the operator resets on the shipped view model then the shipped window returns and one reset row is recorded`() {
        SharedPreferencesThresholdStore(application).save(ThresholdConfig(evidenceTtlMinutes = 240))

        val viewModel = MainViewModel(application)
        assertEquals(
            "The shipped view model installs the persisted window before any evaluation",
            240,
            viewModel.uiState.value.thresholds.evidenceTtlMinutes
        )

        viewModel.resetThresholds()

        await { viewModel.uiState.value.thresholds.evidenceTtlMinutes == 60 }
        assertEquals(60, Thresholds.current().evidenceTtlMinutes)
        assertEquals(60, SharedPreferencesThresholdStore(application).load().evidenceTtlMinutes)

        val reset = ledgerRows().filter { it.reason.contains("RESET") }
        assertEquals(1, reset.size)
        assertTrue("the shipped window must be recorded: ${reset.single().reason}", reset.single().reason.contains("60 min in force"))
    }
}
