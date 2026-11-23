package com.aistudio.alphapulse.engine

import com.aistudio.alphapulse.data.model.CryptoAsset

/**
 * C-22 / C-58 — a universe refresh never repoints the workspace at another coin.
 *
 * Why this exists
 * ---------------
 * The dormant poll rebuilds the campaign universe every twenty seconds and used to fall
 * back to the refreshed list's first row whenever the inspected coin was not in it. The
 * fallback looked harmless while every screened coin was in the universe; once an unproven
 * coin stopped entering it (C-58), the next poll fired it. The operator selected
 * `USDBRLUSDT` in AlphaPulse and the Dashboard showed `SOLUSDT`.
 *
 * Choosing a coin is the operator's action (the AlphaPulse screen is the one selector,
 * C-22), so a refresh may only update the inspected coin's data, never replace it. When the
 * coin is still admitted, the refreshed row is used so its price stays current; when it is
 * not, the last inspected row is kept rather than silently swapped for a different coin.
 *
 * Spec Ref: Phase 2 §72, C-22, C-58
 */
object UniverseSelection {

    /**
     * @param current the coin under inspection, or null before the first selection
     * @param refreshed the freshly built campaign universe
     * @return the row to keep inspecting: the refreshed one when the coin is still
     *   admitted, the current one when it is not, and the first row only when nothing has
     *   been inspected yet
     */
    fun keepInspected(current: CryptoAsset?, refreshed: List<CryptoAsset>): CryptoAsset? {
        if (current == null) return refreshed.firstOrNull()
        return refreshed.firstOrNull { it.symbol == current.symbol } ?: current
    }
}
