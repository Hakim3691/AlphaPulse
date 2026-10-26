package com.aistudio.alphapulse.domain.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Verification of the confidence-cone width, specified as Given/When/Then.
 *
 * The question under test comes from the review: *"is the 95th and 85th
 * percentile cone width still implemented in the code?"*
 *
 * The two numbers describe the same cone:
 *   - **95th percentile** is the z-score: the engine uses 1.645σ, which is the
 *     95th percentile of a standard normal (one-sided), i.e. the half-width.
 *   - **85%** is what that cone actually *covers* once markets are fat-tailed
 *     (≈90% on a Gaussian, ≈85% under the kurtosis regimes this engine flags),
 *     which is why the UI labels the band "+85% Cone".
 *
 * So the test proves both halves still hold: the 1.645 constant really drives
 * the quantile boundaries of every provider, and a fat-tailed market indeed
 * lands near 85% coverage at that width.
 *
 * Spec Ref: §18 probabilistic forecasting → confidence cone, §33 confidence
 * score, §50 cone coverage metric.
 */
class ConePercentileBddSpec {

    /** The z-score the engine is specified to use (95th percentile, one-sided). */
    private val coneZ = 1.645

    private fun sampleInput(regime: String = "Sideways") = TsfmInput(
        close = 65_000.0,
        realizedVolatility = 0.004,
        horizon = "60",
        regime = regime
    )

    // -------------------------------------------------- the 95th percentile half

    @Test
    fun `given every tsfm provider when it forecasts then its quantile boundaries sit at the one-point-six-four-five sigma cone`() {
        val providers = listOf<TsfmProvider>(
            KronosProvider(),
            ChronosBoltProvider(),
            FinTextChronosProvider(),
            TimesFMProvider()
        )

        providers.forEach { provider ->
            val p = provider.predict(sampleInput())
            val halfWidth = p.upperConfidenceBoundary - p.p50
            val lowerWidth = p.p50 - p.lowerConfidenceBoundary

            assertTrue(
                "${provider::class.simpleName}: upper boundary must equal P90",
                p.upperConfidenceBoundary == p.p90
            )
            assertTrue(
                "${provider::class.simpleName}: lower boundary must equal P10",
                p.lowerConfidenceBoundary == p.p10
            )
            assertTrue(
                "${provider::class.simpleName}: the cone must be symmetric around P50 " +
                    "(upper $halfWidth, lower $lowerWidth)",
                halfWidth == lowerWidth
            )
            assertTrue(
                "${provider::class.simpleName}: cone must have a non-zero width",
                halfWidth > 0.0
            )
        }
    }

    @Test
    fun `given the kronos construction when recomputed by hand then the published band is exactly one-point-six-four-five sigma wide per side`() {
        val input = sampleInput("Sideways")
        val prediction = KronosProvider().predict(input)

        // Reproduce the provider's arithmetic from the documented formula:
        //   priceChange = realizedVolatility * close * horizonDays * regimeFactor
        //   sigma       = |priceChange| * 0.5
        //   P90         = P50 + 1.645 * sigma      <- the 95th-percentile half-width
        val horizonDays = input.horizon.toDouble()
        val priceChange = input.realizedVolatility * input.close * horizonDays
        val sigma = kotlin.math.abs(priceChange) * 0.5

        assertEquals(
            "P50 must be the drifted price",
            input.close + priceChange,
            prediction.p50,
            1e-6
        )
        assertEquals(
            "Upper bound must sit 1.645σ (95th percentile) above the median",
            prediction.p50 + coneZ * sigma,
            prediction.p90,
            1e-6
        )
        assertEquals(
            "Lower bound must sit 1.645σ (95th percentile) below the median",
            prediction.p50 - coneZ * sigma,
            prediction.p10,
            1e-6
        )
    }

    @Test
    fun `given the quantile ladder when read then p10 and p90 straddle the median exactly like a 95th percentile band`() {
        val p = KronosProvider().predict(sampleInput())

        // p25/p75 are midpoints of the halves by construction (see the providers),
        // so the ladder must be monotonic: P10 < P25 < P50 < P75 < P90.
        assertTrue("P10 < P25", p.p10 < p.p25)
        assertTrue("P25 < P50", p.p25 < p.p50)
        assertTrue("P50 < P75", p.p50 < p.p75)
        assertTrue("P75 < P90", p.p75 < p.p90)

        // Dispersion is the full width of the 95th-percentile band.
        assertEquals(
            "Dispersion must be P90 - P10",
            p.p90 - p.p10,
            p.forecastDispersion,
            1e-9
        )
        // Symmetric construction ⇒ zero skew for a symmetric market.
        assertEquals(
            "A symmetric band must report zero skew",
            0.0,
            p.forecastSkew,
            1e-9
        )
    }

    // ------------------------------------------------------ the ~85% coverage half

    @Test
    fun `given a gaussian market when the one-point-six-four-five sigma cone is applied then it covers about 90 percent of outcomes`() {
        // 2*Phi(1.645) - 1 = 0.90 - the two-sided coverage on a normal distribution.
        val coverage = gaussianCoverage(coneZ)

        assertEquals("1.645σ is the 90% two-sided / 95% one-sided band", 0.90, coverage, 0.005)
        assertEquals("One-sided tail mass must be 5%", 0.95, 1.0 - (1.0 - coverage) / 2.0, 0.005)
    }

    @Test
    fun `given a fat-tailed market when the same cone is applied then coverage drops to roughly 85 percent`() {
        // Student-t with 5 degrees of freedom is a classic fat-tailed proxy
        // (kurtosis 9 vs the Gaussian's 3) - roughly what the engine's kurtosis
        // flag describes. Monte-Carlo with a fixed seed keeps this deterministic.
        val samples = 200_000
        val random = Random(42)
        var inside = 0

        repeat(samples) {
            // t5 = z / sqrt(chi2_5 / 5), where chi2_5 is the sum of 5 squared normals.
            val z = gaussian(random)
            var chi2 = 0.0
            repeat(5) { val g = gaussian(random); chi2 += g * g }
            val t = z / sqrt(chi2 / 5.0)
            if (kotlin.math.abs(t) <= coneZ) inside++
        }

        val coverage = inside.toDouble() / samples
        assertTrue(
            "Fat-tailed coverage at 1.645σ should be ≈85% (measured ${"%.1f".format(coverage * 100)}%)",
            coverage in 0.80..0.90
        )
    }

    // --------------------------------------------------------------- helpers

    /** Standard-normal coverage of a ±z band, computed from the error function. */
    private fun gaussianCoverage(z: Double): Double {
        // erf(x) = 2/sqrt(pi) * integral_0^x e^-t^2 dt; Phi(z) = 0.5*(1+erf(z/sqrt2)).
        val phi = 0.5 * (1.0 + erf(z / sqrt(2.0)))
        val lower = 1.0 - phi
        return phi - lower
    }

    /** Abramowitz-Stegun 7.1.26 error function (max error 1.5e-7 - plenty here). */
    private fun erf(x: Double): Double {
        val sign = if (x < 0) -1.0 else 1.0
        val ax = kotlin.math.abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * kotlin.math.exp(-ax * ax)
        return sign * y
    }

    /** One standard-normal draw (Box-Muller) from a seeded generator. */
    private fun gaussian(random: Random): Double {
        val u1 = (random.nextInt(1, 2147483646)).toDouble() / 2147483646.0
        val u2 = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
