package com.eried.eucplanet.ui.charging

import com.eried.eucplanet.data.model.BmsState
import com.eried.eucplanet.data.repository.MetricSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The charging monitor's Export file reads the same on both phones. */
class ChargingCsvTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun noSessionNoFile() {
        assertNull(ChargingCsv.build(ChargingUiState()))
    }

    @Test
    fun rowsCarryTheSeriesAndThePacks() {
        val state = ChargingUiState(
            chargeHistory = listOf(MetricSample(t0, 41.5f), MetricSample(t0 + 60_000, 42.25f)),
            voltageHistory = listOf(MetricSample(t0, 120.4f), MetricSample(t0 + 60_000, 120.55f)),
            tempHistory = listOf(MetricSample(t0, 31f)),
            packMinHistory = listOf(listOf(MetricSample(t0, 3.9f))),
            packMaxHistory = listOf(listOf(MetricSample(t0, 4.012f))),
            packAvgHistory = listOf(listOf(MetricSample(t0, 3.95f))),
            packSeriesUnit = "V",
        )
        val lines = ChargingCsv.build(state)!!.trimEnd().lines()
        assertEquals("time,elapsed_s,charge_pct,voltage_v,temp_c,pack1_min_V,pack1_max_V,pack1_avg_V", lines[0])
        assertTrue(lines[1].matches(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d,0,41\.50,120\.400,31\.0,3\.900,4\.012,3\.950""")), lines[1])
        // A series that ran out leaves its cells empty rather than repeating.
        assertTrue(lines[2].endsWith(",60,42.25,120.550,,,,"), lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun theLatestCellsFollowAsABlock() {
        val state = ChargingUiState(
            chargeHistory = listOf(MetricSample(t0, 80f)),
            bms = BmsState(packs = listOf(BmsState.PackState(0, cellVoltages = listOf(4.101f, 0f, 4.099f)))),
        )
        val text = ChargingCsv.build(state)!!
        assertTrue(text.endsWith("\npack,cell,voltage_v\n1,1,4.101\n1,2,4.099\n"), text)
    }
}
