package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.ExecutionMode
import com.aistudio.alphapulse.data.model.VenueHealth

/**
 * C-63 — venue and hardware health block routing, not planning.
 *
 * Every board was suppressed with `STAGING_GATE_PAPER / Venue or hardware health
 * guardrail tripped` because `VenueHealth.isHealthy` folded four different signals into
 * one verdict: an optional feed answering slowly (CoinGlass at its 9,999ms unreachable
 * sentinel), a Binance latency spike, and device power/thermal state all read as a
 * refusal. Paper and Shadow route nothing, so those are advisories there — reported,
 * but not a reason to stop planning. A live mode still refuses, and a stale market feed
 * refuses in every mode because no mode can work from stale prices.
 *
 * The kill switch and daily-loss stop are not venue health and stay in `RiskEngine`.
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
            val advisories = mutableListOf<String>()

            fun gate(condition: Boolean, hard: String, advisory: String) {
                if (!condition) return
                if (plansOnly) advisories += advisory else return
            }

            // Collected first, so a live mode reports the first hard block by severity
            // order and a planning mode still reports every advisory.
            val hardBlocks = mutableListOf<String>()

            if (health.isFeedStale) {
                hardBlocks += "Market feed is stale (${health.feedStalenessSec}s) — staging is paused until it recovers."
            }
            if (health.isLatencySpike) {
                val latency = "Venue latency spike (Binance ${health.binancePingMs}ms, " +
                    "CoinGlass ${health.coinglassPingMs}ms)"
                if (plansOnly) {
                    advisories += "$latency — planning continues, live routing would hold."
                } else {
                    hardBlocks += "$latency — live routing holds until it clears."
                }
            }
            if (health.isLowPowerMode || health.isThermallyThrottled) {
                val device = "Device is ${if (health.isThermallyThrottled) "thermally throttled" else "in low-power mode"}"
                if (plansOnly) {
                    advisories += "$device — planning continues, live routing would hold."
                } else {
                    hardBlocks += "$device — live routing holds until the device recovers."
                }
            }
            if (health.isRamConstrained) {
                if (plansOnly) {
                    advisories += "Memory is constrained (${health.availableRamMb}MB available) — planning continues."
                } else {
                    hardBlocks += "Memory is constrained (${health.availableRamMb}MB available) — live routing holds."
                }
            }

            return VenueHealthVerdict(
                hardBlock = hardBlocks.firstOrNull(),
                advisories = advisories
            )
        }
    }
}
