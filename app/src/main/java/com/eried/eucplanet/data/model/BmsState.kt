package com.eried.eucplanet.data.model

/**
 * Aggregated smart-BMS state, stitched together from multiple BMS sub-frames
 * over time (each sub-frame carries only a 12-15 cell window plus a temp /
 * current header). One [PackState] per physical pack — single-pack wheels
 * (Lynx, Sherman L, NOSFET Apex) report one pack; the Oryx reports two.
 *
 * Empty list means "no smart-BMS wheel connected / no BMS data received yet".
 * The Battery monitor's Cells tab keys off [packs.isNotEmpty] to decide
 * whether to show.
 */
data class BmsState(
    val packs: List<PackState> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val hasCells: Boolean get() = packs.any { it.cellVoltages.any { v -> v > 0f } }

    data class PackState(
        val packIndex: Int,
        /** Indexed by absolute cell number across the pack. 0f means "not yet
         *  reported for this cell" (waiting for the next page rotation). */
        val cellVoltages: List<Float> = emptyList(),
        /** BMS-reported temperatures in Celsius, one entry per sensor. */
        val temperaturesC: List<Float> = emptyList(),
        /** Per-pack current in A (negative = charging). Null if the wheel
         *  hasn't reported it yet for this pack. */
        val currentA: Float? = null,
    ) {
        /** Cells the wheel has actually reported (filter zero placeholders). */
        val knownCells: List<Pair<Int, Float>> get() = cellVoltages
            .mapIndexed { i, v -> i to v }
            .filter { it.second > 0f }

        val cellCount: Int get() = knownCells.size
        val minCellV: Float? get() = knownCells.minOfOrNull { it.second }
        val maxCellV: Float? get() = knownCells.maxOfOrNull { it.second }
        /** Cell-balance delta in mV. Lower is better; > 50 mV is usually flagged
         *  as needing balance on EUCs. */
        val cellDeltaMv: Int? get() {
            val mn = minCellV ?: return null
            val mx = maxCellV ?: return null
            return ((mx - mn) * 1000f).toInt()
        }
    }
}
