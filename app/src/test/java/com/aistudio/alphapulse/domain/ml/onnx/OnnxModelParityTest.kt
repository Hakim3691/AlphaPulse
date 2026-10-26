package com.aistudio.alphapulse.domain.ml.onnx

import com.aistudio.alphapulse.data.model.ModelArtifactInstaller
import com.aistudio.alphapulse.data.model.ModelArtifactSource
import com.aistudio.alphapulse.data.model.ModelArtifacts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import com.aistudio.alphapulse.domain.ml.TsfmInput
import kotlin.math.sin

/**
 * Executes the actual published ONNX graphs through the production adapters.
 *
 * Skipped in ordinary builds; CI enables the 10 MB Chronos run with
 * `VERIFY_ARTIFACT_NETWORK=true`. The 250 MB TimesFM run is opt-in via
 * `VERIFY_LARGE_MODEL=true`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnnxModelParityTest {

    @Test
    fun `given the published chronos artifact when forecast then finite quantiles are returned`() =
        runTest {
            assumeTrue(
                "Set VERIFY_ARTIFACT_NETWORK=true to execute the real graphs",
                System.getProperty("verifyArtifactNetwork") == "true"
            )

            val closes = syntheticCloses()
            val source = ModelArtifacts.CHRONOS
            withInstalled(source) { artifact ->
                val forecaster = forecasterFor(source, artifact)
                forecaster.syncMountedModels(listOf(source.modelId))

                val forecast = forecaster.forecast(source.modelId, TsfmInput(), closes, 60)

                assertNotNull("Chronos returned no forecast", forecast)
                assertPlausible(forecast!!, closes.last())
                assertTrue(
                    "Chronos bands must be finite prices",
                    forecast.lower.all { it.isFinite() && it > 0.0 } &&
                        forecast.upper.all { it.isFinite() && it > 0.0 }
                )
                assertNotNull(forecast.driftPerMinute(closes.last(), 5))
            }
        }

    @Test
    fun `given the published timesfm artifact when forecast then the mean head is returned`() =
        runTest {
            assumeTrue(
                "Set VERIFY_LARGE_MODEL=true to execute the 250 MB TimesFM graph",
                System.getProperty("verifyLargeModel") == "true"
            )

            val closes = syntheticCloses()
            val source = ModelArtifacts.TIMESFM
            withInstalled(source) { artifact ->
                val forecaster = forecasterFor(source, artifact)
                forecaster.syncMountedModels(listOf(source.modelId))

                val forecast = forecaster.forecast(source.modelId, TsfmInput(), closes, 60)

                assertNotNull("TimesFM returned no forecast", forecast)
                assertPlausible(forecast!!, closes.last())
                assertNotNull(forecast.driftPerMinute(closes.last(), 30))
            }
        }

    private fun assertPlausible(forecast: TsfmForecast, spot: Double) {
        assertEquals(60, forecast.median.size)
        assertTrue(
            "Forecast must stay finite and positive",
            forecast.median.all { it.isFinite() && it > 0.0 }
        )
        assertTrue(
            "De-normalized forecast drifted implausibly far from spot",
            forecast.median.all { it > spot * 0.5 && it < spot * 1.5 }
        )
    }

    private fun forecasterFor(source: ModelArtifactSource, artifact: File) =
        OnnxTsfmForecaster { requested ->
            if (requested == source.modelId) artifact else null
        }

    private suspend fun withInstalled(
        source: ModelArtifactSource,
        block: suspend (File) -> Unit
    ) {
        val dir = Files.createTempDirectory("onnx-parity-${source.modelId}").toFile()
        try {
            val artifact = ModelArtifactInstaller().install(source, File(dir, source.fileName))
            block(artifact)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 400 one-minute closes: mild uptrend plus a daily-ish sine. */
    private fun syntheticCloses(): DoubleArray =
        DoubleArray(400) { i -> 100.0 + i * 0.02 + sin(i / 9.0) * 0.6 }
}
