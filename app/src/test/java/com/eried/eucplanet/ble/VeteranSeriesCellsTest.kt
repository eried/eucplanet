package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the per-model series-cell count used to cap the Veteran BMS cell view.
 * The long-frame always carries 42 slots; capping to these counts stops shorter
 * packs (e.g. the 36S NOSFET Aeon) from rendering an empty tail as phantom red
 * cells (github issue #10).
 */
class VeteranSeriesCellsTest {

    @Test fun `series cell count matches the pack voltage`() {
        assertEquals(24, VeteranModel.SHERMAN.seriesCells)      // 100 V
        assertEquals(24, VeteranModel.ABRAMS.seriesCells)       // 100 V
        assertEquals(32, VeteranModel.PATTON.seriesCells)       // 134 V
        assertEquals(32, VeteranModel.SHERMAN_MAX.seriesCells)  // 134 V
        assertEquals(36, VeteranModel.LYNX.seriesCells)         // 151 V
        assertEquals(36, VeteranModel.NOSFET_AEON.seriesCells)  // 151 V  <- the reported wheel
        assertEquals(36, VeteranModel.NOSFET_APEX.seriesCells)  // 151 V
        assertEquals(42, VeteranModel.ORYX.seriesCells)         // 175 V  (fills all 42 slots)
    }

    @Test fun `Aeon caps a full-frame 42-cell read down to 36`() {
        // A pnum 3/7 slice covers cells 30..41 (12 slots); a 36S pack keeps only
        // 30..35, i.e. 6 of them. seriesCells - rangeStart = 36 - 30 = 6.
        val keep = (VeteranModel.NOSFET_AEON.seriesCells - 30).coerceAtLeast(0)
        assertEquals(6, keep)
        // The Oryx keeps all 12 (30..41) -> 42S.
        assertEquals(12, (VeteranModel.ORYX.seriesCells - 30).coerceAtLeast(0))
    }
}
