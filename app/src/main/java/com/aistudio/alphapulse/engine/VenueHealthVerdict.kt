package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.VenueHealth

/**
 * C-63 — venue and hardware health block routing, not planning.
 *
 * Every board was suppressed with `STAGING_GATE_PAPER / Venue or hardware health
 * guardrail tripped` because `VenueHealth.isHealthy` folded four different signals into
 * one verdict. A stale execution feed, a Binance latency spike, an unreachable *optional*
 * CoinGlass probe, and device power/thermal/RAM state all read as the same refusal.
 *
 * The split is now explicit:
 * - **Hard for every mode:** the mandatory Binance execution feed is stale/unreachable,
 *   the device is thermally throttled, or memory is below the floor. No mode can plan or
 *   route reliably from a stale price, a throttled device, or a starved process.
 * - **Hard only for live modes:** Binance latency spike, low-power mode. Paper and Shadow
 *   route nothing, so those are advisories there; a live mode refuses and names them.
 * - **Advisory in every mode:** the optional CoinGlass probe being slow or unreachable.
 *   It gates the heatmap precondition, not staging.
 *
 * Kill switch and daily-loss stops are not venue health and stay in `RiskEngine`.
 *
 * Spec Ref: §2, §22, §23, §36, C-63
 */
data class VenueHealthVerdict(
    val hardBlock: String?,
    val advisories: List<String>
) {

    val canStage: Boolean get() = hardBlock == null

    companion object {

        /**
         * @param mode the operator's execution mode: SHADOW/PAPER may plan through a
         *   degraded venue, the live modes may not route through one
         */
        fun assess(health: VenueHealth, mode: ExecutionMode): VenueHealthVerdict {
            val plansOnly = mode == ExecutionMode.SHADOW || mode == ExecutionMode.PAPER
            val hardBlocks = mutableListOf<String>()
            val advisories = mutableListOf<String>()

            fun planOnly(condition: Boolean, hard: String, advisory: String) {
                if (!condition) return
                if (plansOnly) advisories += advisory else hardBlocks += hard
            }

            // The mandatory execution feed: hard everywhere.
            if (health.isFeedStale) {
                hardBlocks += "Market feed is stale (${health.feedStalenessSec}s) — staging is paused until it recovers."
            }

            // Mandatory hardware floors: hard everywhere (Spec §2).
            if (health.isThermallyThrottled) {
                hardBlocks += "Device is thermally throttled — staging is paused until it cools."
            }
            if (health.isRamConstrained) {
                hardBlocks += "Memory is constrained (${health.availableRamMb}MB available, floor is 800MB) — staging is paused."
            }

            // Execution-venue latency: a routing concern only.
            if (health.isLatencySpike) {
                val latency = "Binance latency spike (${health.binancePingMs}ms)"
                planOnly(
                    condition = true,
                    hard = "$latency — live routing holds until it clears.",
                    advisory = "$latency — planning continues, live routing would hold."
                )
            }

            // Low power: advisory while planning, hard while routing.
            if (health.isLowPowerMode) {
                planOnly(
                    condition = true,
                    hard = "Device is in low-power mode — live routing holds until it is disabled.",
                    advisory = "Device is in low-power mode — planning continues, live routing would hold."
                )
            }

            // The optional CoinGlass probe is never a staging block: it gates the heatmap.
            if (health.coinglassPingMs >= SessionSecurityManager.LATENCY_SPIKE_COINGLASS_MS) {
                advisories += "Optional CoinGlass feed is slow or unreachable " +
                    "(${health.coinglassPingMs}ms) — heatmap precondition may be unmet; staging is unaffected."
            }

            return VenueHealthVerdict(
                hardBlock = hardBlocks.firstOrNull(),
                advisories = advisories
            )
        }
    }
}
