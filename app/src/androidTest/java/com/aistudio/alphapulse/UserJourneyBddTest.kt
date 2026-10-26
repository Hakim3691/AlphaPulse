package com.aistudio.alphapulse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aistudio.alphapulse.data.db.AppDatabase
import com.aistudio.alphapulse.data.db.PredictionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * User-level ("does it work for a human?") acceptance tests.
 *
 * These run on a real device or emulator, not on the JVM, so they check the
 * things JVM tests cannot: that the app really launches, that Compose renders
 * on screen, and that Room writes actually survive on disk.
 *
 * Each test name reads as a sentence - Given the situation, When the user does
 * something, Then this is what they see - because these describe behaviour a
 * person can observe, not internal wiring.
 *
 * Spec Ref: §52 Testing Requirements (user testing), §63 Definition of Done
 */
@RunWith(AndroidJUnit4::class)
class UserJourneyBddTest {

    /**
     * Boots the real application activity. Nothing is mocked here: if the app
     * crashes on start, this rule fails before any test body runs.
     */
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    // ------------------------------------------------------------ journey 1

    /**
     * Given a cold install, when the app opens, then the workspace header is
     * visible - which means the main screen composed instead of crashing.
     */
    @Test
    fun appHeaderIsVisibleOnLaunch() {
        composeRule.onNodeWithText("AlphaPulse").assertIsDisplayed()
    }

    // ------------------------------------------------------------ journey 2

    /**
     * Given a saved prediction, when the app is restarted, then the prediction
     * is still on disk. The database is a real file (not in-memory) so this
     * proves persistence itself works.
     */
    @Test
    fun savedPredictionSurvivesRestart() {
        val dbName = "journey-test.db"
        context.deleteDatabase(dbName)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        database = db

        val prediction = PredictionEntity(
            id = "journey-1",
            symbol = "BTCUSDT",
            horizonId = "1m",
            horizonLabel = "1 min",
            modelType = "Tri-Factor Ensemble",
            minutes = 1,
            issuedTimestamp = 1_700_000_000_000L,
            targetTimestamp = 1_700_000_060_000L,
            basePrice = 100.0,
            predictedPrice = 101.0,
            upperBound = 102.0,
            lowerBound = 99.0,
            muK = 1.0,
            muP = 0.5,
            muAlpha = -0.5,
            settled = false,
            versionTag = "journey"
        )

        runBlocking { db.predictionDao().insert(prediction) }
        db.close()

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        database = reopened
        val stored = runBlocking { reopened.predictionDao().getPredictionsBySymbol("BTCUSDT") }

        assertEquals("The saved prediction must survive a restart", 1, stored.size)
        assertEquals("journey-1", stored.first().id)
        assertTrue("Predicted price must round-trip", stored.first().predictedPrice == 101.0)

        context.deleteDatabase(dbName)
    }
}
