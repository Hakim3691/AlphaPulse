package com.aistudio.alphapulse.domain.presentation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-65 — a forecast is withheld, never zero-filled, when there is no usable live price.
 *
 * The released build rendered `$0.00000` targets and `▲NaN%` pills in the OFFLINE SEED
 * state, and the trajectory chart drew an empty `1.0..2.0` grid. `currentSpot` was 0.0,
 * so `deltaPct` was `0/0`. These scenarios pin the rule and the source path that uses it.
 */
class ForecastAvailabilityBddSpec {

    @Test
    fun `given a zero NaN negative or infinite spot when the forecast is assessed then it is withheld with a reason`() {
        for (spot in listOf(0.0, Double.NaN, -1.0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val availability = ForecastAvailabilityPolicy.assess(spot)

            assertFalse("spot=$spot must not be forecast", availability.available)
            assertNotNull("spot=$spot must say why the forecast is withheld", availability.reason)
            assertEquals(
                ForecastAvailabilityPolicy.NO_LIVE_PRICE_REASON,
                availability.reason
            )
        }
    }

    @Test
    fun `given a positive finite spot when the forecast is assessed then it is available`() {
        for (spot in listOf(0.07731, 1.4083, 64_520.12)) {
            val availability = ForecastAvailabilityPolicy.assess(spot)

            assertTrue("spot=$spot must be forecast", availability.available)
            assertEquals(null, availability.reason)
        }
    }

    @Test
    fun `given a non-finite delta when the pill is formatted then it is a withheld marker and never NaN`() {
        assertEquals("—", ForecastAvailabilityPolicy.deltaLabel(Double.NaN, isBullish = true))
        assertEquals("—", ForecastAvailabilityPolicy.deltaLabel(Double.POSITIVE_INFINITY, isBullish = false))
    }

    @Test
    fun `given a finite delta when the pill is formatted then it keeps the arrow and two decimals`() {
        assertEquals("▲1.23%", ForecastAvailabilityPolicy.deltaLabel(1.234, isBullish = true))
        assertEquals("▼4.50%", ForecastAvailabilityPolicy.deltaLabel(-4.5, isBullish = false))
    }

    @Test
    fun `given the shipped sources when read then the repository and UI both use the availability rule`() {
        val repository = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/data/repository/AlphaPulseRepository.kt"
        ).readText()
        val accordions = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/components/AccordionsSection.kt"
        ).readText()
        val screen = repoFile(
            "app/src/main/java/com/aistudio/alphapulse/ui/AlphaPulseScreen.kt"
        ).readText()

        assertTrue(
            "The cycle must assess the spot before issuing targets",
            repository.contains("ForecastAvailabilityPolicy.assess(currentSpot)")
        )
        assertTrue(
            "No forward chart point may be added when the forecast is withheld",
            repository.contains("if (forecastAvailability.available) forecastTargets else emptyList()")
        )
        assertTrue(
            "The ensemble panel must render the withheld state",
            accordions.contains("ForecastUnavailableNotice(") &&
                accordions.contains("ForecastAvailabilityPolicy.assess(spotPrice)")
        )
        assertTrue(
            "The delta pill must use the safe formatter",
            accordions.contains("ForecastAvailabilityPolicy.deltaLabel(")
        )
        assertTrue(
            "The screen must pass the live spot into the accordions",
            screen.contains("spotPrice = uiState.spotPrice")
        )
    }

    private fun repoFile(relativePath: String): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                if (!file.exists()) throw AssertionError("Missing repository file: $relativePath")
                return file
            }
            directory = directory.parentFile
        }
        throw AssertionError("Could not locate the repository root")
    }
}
