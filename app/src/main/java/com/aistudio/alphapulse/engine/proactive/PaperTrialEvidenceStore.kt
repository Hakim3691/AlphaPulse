package com.aistudio.alphapulse.engine.proactive

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/** Where the §38 paper-trial evidence is persisted between app processes. */
interface PaperTrialEvidenceStore {
    fun read(): String?
    fun write(payload: String)
    fun clear()
}

/**
 * The durable form of [PaperTrialEvidenceTracker]'s state.
 *
 * A hand-rolled, versioned text format is used instead of JSON so the production
 * code and the JVM unit tests share exactly one parser (no Android-only `org.json`
 * stub) and so a partially written or hand-edited payload is detectable:
 *
 *  - the first line is an exact format header, so a future format is rejected rather
 *    than misread;
 *  - every field is parsed strictly, with bounds, and duplicate/unknown keys fail;
 *  - a CRC32 of the body follows, so a truncated write or a tampered number cannot
 *    silently load as a larger streak;
 *  - [decode] returns null on any doubt, and the tracker treats null as "no evidence"
 *    (locked), never as a clean slate that unlocks Scaled Live.
 *
 * Spec Ref: Phase 2 §38
 */
object PaperTrialEvidenceCodec {

    const val HEADER = "PAPER_TRIAL_EVIDENCE_V1"
    const val MAX_LATENCY_SAMPLES = 200
    const val MAX_TRADES = 1_000_000
    const val MAX_EVENTS = 1_000_000
    const val MAX_LATENCY_MS = 600_000L

    data class Snapshot(
        val consecutiveCompletedTrades: Int,
        val exceptionsInCurrentStreak: Int,
        val latenciesMs: List<Long>,
        val minimizeEventsTested: Int,
        val minimizeEventsWarnedAndWiped: Int,
        val baseViewObserved: Boolean,
        val baseViewRegressionDetected: Boolean
    )

    fun encode(snapshot: Snapshot): String {
        val body = listOf(
            HEADER,
            "trades=${snapshot.consecutiveCompletedTrades}",
            "exceptions=${snapshot.exceptionsInCurrentStreak}",
            "tested=${snapshot.minimizeEventsTested}",
            "wiped=${snapshot.minimizeEventsWarnedAndWiped}",
            "base_observed=${snapshot.baseViewObserved}",
            "base_regression=${snapshot.baseViewRegressionDetected}",
            "latencies=${snapshot.latenciesMs.joinToString(",")}"
        ).joinToString("\n")
        return body + "\ncrc=" + checksum(body)
    }

    /** Null means corrupt, truncated, stale-format or otherwise untrustworthy. */
    fun decode(payload: String?): Snapshot? {
        if (payload.isNullOrBlank()) return null
        return try {
            val lines = payload.trim().split("\n")
            if (lines.size != 9 || lines.first() != HEADER) return null

            val crcLine = lines.last()
            if (!crcLine.startsWith("crc=")) return null
            val body = lines.dropLast(1).joinToString("\n")
            if (crcLine.removePrefix("crc=") != checksum(body)) return null

            val fields = mutableMapOf<String, String>()
            for (line in lines.subList(1, lines.size - 1)) {
                val separator = line.indexOf('=')
                if (separator <= 0) return null
                val key = line.substring(0, separator)
                if (fields.containsKey(key)) return null
                fields[key] = line.substring(separator + 1)
            }
            val expectedKeys = setOf(
                "trades", "exceptions", "tested", "wiped",
                "base_observed", "base_regression", "latencies"
            )
            if (fields.keys != expectedKeys) return null

            val trades = boundedInt(fields["trades"], MAX_TRADES) ?: return null
            val exceptions = boundedInt(fields["exceptions"], MAX_TRADES) ?: return null
            val tested = boundedInt(fields["tested"], MAX_EVENTS) ?: return null
            val wiped = boundedInt(fields["wiped"], MAX_EVENTS) ?: return null
            if (wiped > tested) return null

            val observed = when (fields["base_observed"]) {
                "true" -> true
                "false" -> false
                else -> return null
            }
            val regression = when (fields["base_regression"]) {
                "true" -> true
                "false" -> false
                else -> return null
            }

            val rawLatencies = fields["latencies"].orEmpty()
            val latencies = if (rawLatencies.isEmpty()) {
                emptyList()
            } else {
                rawLatencies.split(",").map { raw ->
                    raw.toLongOrNull()?.takeIf { it in 0L..MAX_LATENCY_MS } ?: return null
                }
            }
            if (latencies.size > MAX_LATENCY_SAMPLES) return null

            Snapshot(
                consecutiveCompletedTrades = trades,
                exceptionsInCurrentStreak = exceptions,
                latenciesMs = latencies,
                minimizeEventsTested = tested,
                minimizeEventsWarnedAndWiped = wiped,
                baseViewObserved = observed,
                baseViewRegressionDetected = regression
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun boundedInt(raw: String?, max: Int): Int? =
        raw?.toIntOrNull()?.takeIf { it in 0..max }

    private fun checksum(body: String): String {
        val crc = CRC32()
        crc.update(body.toByteArray(StandardCharsets.UTF_8))
        return crc.value.toString(16)
    }
}
