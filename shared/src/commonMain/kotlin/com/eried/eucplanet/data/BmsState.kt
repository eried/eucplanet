package com.eried.eucplanet.data

import com.eried.eucplanet.ble.DecodeResult
import com.eried.eucplanet.util.nowEpochMillis

/**
 * Aggregated smart-BMS state, stitched from multiple BMS sub-frames over time
 * (each sub-frame carries only a 12-15 cell window plus a temp / current header).
 * One [PackState] per physical pack — single-pack wheels report one, the Oryx two.
 * Port of Android's data.model.BmsState (kept in the `data` package here to dodge
 * the FQN collision with Android's own BmsState, like AlarmRule). Empty packs =
 * no smart-BMS data yet; the Battery screen's Cells section keys off [hasCells].
 */
data class BmsState(
    val packs: List<PackState> = emptyList(),
    val updatedAt: Long = 0L,
) {
    val hasCells: Boolean get() = packs.any { it.cellVoltages.any { v -> v > 0f } }

    data class PackState(
        val packIndex: Int,
        /** Indexed by absolute cell number; 0f = not yet reported (page rotation). */
        val cellVoltages: List<Float> = emptyList(),
        /** BMS-reported temperatures in Celsius, one per sensor. */
        val temperaturesC: List<Float> = emptyList(),
        /** Per-pack current in A (negative = charging); null until reported. */
        val currentA: Float? = null,
    ) {
        val knownCells: List<Pair<Int, Float>> get() = cellVoltages
            .mapIndexed { i, v -> i to v }.filter { it.second > 0f }
        val cellCount: Int get() = knownCells.size
        val minCellV: Float? get() = knownCells.minOfOrNull { it.second }
        val maxCellV: Float? get() = knownCells.maxOfOrNull { it.second }
        /** Cell-balance delta in mV. > 50 mV usually flags as needing balance. */
        val cellDeltaMv: Int? get() {
            val mn = minCellV ?: return null
            val mx = maxCellV ?: return null
            return ((mx - mn) * 1000f).toInt()
        }
    }
}

/**
 * Stitch a fresh BMS slice into the per-pack rolling state — port of Android's
 * mergeBmsSlice. Each slice covers a cell window (landed by absolute index so a
 * 30..41 slice doesn't disturb 0..29), a pack-current header, or 6 temperatures.
 */
fun mergeBmsSlice(prev: BmsState, slice: DecodeResult.Bms): BmsState {
    val existing = prev.packs.firstOrNull { it.packIndex == slice.packIndex }
        ?: BmsState.PackState(packIndex = slice.packIndex)
    val cells = existing.cellVoltages.toMutableList()
    val sliceCells = slice.cellVoltages
    val rangeStart = slice.cellRangeStart
    if (sliceCells != null && rangeStart != null) {
        val needed = rangeStart + sliceCells.size
        while (cells.size < needed) cells.add(0f)
        for (i in sliceCells.indices) {
            val v = sliceCells[i]
            if (v > 0f) cells[rangeStart + i] = v
        }
    }
    val newCurrent = when (slice.packIndex) {
        0 -> slice.packCurrent1A ?: existing.currentA
        1 -> slice.packCurrent2A ?: existing.currentA
        else -> existing.currentA
    }
    val updated = existing.copy(
        cellVoltages = cells,
        temperaturesC = slice.bmsTempsC ?: existing.temperaturesC,
        currentA = newCurrent,
    )
    val others = prev.packs.filter { it.packIndex != slice.packIndex }
    return BmsState(packs = (others + updated).sortedBy { it.packIndex }, updatedAt = nowEpochMillis())
}
