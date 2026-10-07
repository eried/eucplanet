package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.BatteryPercentSettings
import com.eried.eucplanet.util.BatteryPercentEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drift guard for the cells-in-series derivation.
 *
 * Cells come from the charged pack voltage each family already records for its
 * models, so no family needs battery-specific code of its own. That only holds
 * while every model table keeps recording a *charged* voltage: a row carrying a
 * 3.6 V/cell nominal instead would derive too few cells and hand the rider an
 * optimistic percentage. The per-cell band below is what catches that.
 */
class SeriesCellsDerivationTest {

    /** Charged lithium sits at 4.2 V; a nominal figure lands near 3.6 V. */
    private val plausibleCellVoltage = 4.15f..4.25f

    private fun checkTable(family: String, rows: List<Pair<String, Int?>>) {
        for ((name, nominal) in rows) {
            if (nominal == null) continue
            val cells = BatteryPercentEstimator.seriesCellsFor(nominal)
            assert(cells in BatteryPercentSettings.SERIES_RANGE) {
                "$family $name: $nominal V derives ${cells}S, outside the settable range"
            }
            val perCell = nominal / cells.toFloat()
            assert(perCell in plausibleCellVoltage) {
                "$family $name: $nominal V over ${cells}S is $perCell V/cell. " +
                    "The column must hold the charged pack voltage, not a nominal one"
            }
        }
    }

    @Test
    fun `the ladder every family shares`() {
        assertEquals(16, BatteryPercentEstimator.seriesCellsFor(67))
        assertEquals(20, BatteryPercentEstimator.seriesCellsFor(84))
        assertEquals(24, BatteryPercentEstimator.seriesCellsFor(100))
        assertEquals(30, BatteryPercentEstimator.seriesCellsFor(126))
        assertEquals(32, BatteryPercentEstimator.seriesCellsFor(134))
        assertEquals(36, BatteryPercentEstimator.seriesCellsFor(151))
        assertEquals(42, BatteryPercentEstimator.seriesCellsFor(175))
        assertEquals(42, BatteryPercentEstimator.seriesCellsFor(176))
        assertEquals(50, BatteryPercentEstimator.seriesCellsFor(210))
    }

    @Test
    fun `every KingSong model derives a sane pack`() {
        checkTable("KingSong", KingsongModel.entries.map { it.name to it.nominalVoltage })
    }

    @Test
    fun `every Veteran model derives a sane pack`() {
        checkTable("Veteran", VeteranModel.entries.map { it.name to it.nominalVoltage })
    }

    @Test
    fun `every Begode model derives a sane pack`() {
        checkTable("Begode", BegodeModel.entries.map { it.name to it.nominalVoltage })
    }

    @Test
    fun `every Ninebot model derives a sane pack`() {
        checkTable("Ninebot", NinebotModel.entries.map { it.name to it.nominalVoltage })
    }

    @Test
    fun `every InMotion V1 model derives a sane pack`() {
        checkTable("InMotion V1", InMotionV1Model.entries.map { it.name to it.nominalVoltage })
    }

    @Test
    fun `KingSong keeps the counts it used to spell out`() {
        // The column was hand-typed until the derivation replaced it; these are
        // the numbers it held, so the swap cannot have moved anyone's reading.
        val was = mapOf(
            KingsongModel.KS14 to 16, KingsongModel.KS16 to 16,
            KingsongModel.KS_16X to 20, KingsongModel.KS_16S to 20,
            KingsongModel.KS18 to 20, KingsongModel.KS_S16 to 20,
            KingsongModel.KS_S18 to 20, KingsongModel.KS_S19 to 24,
            KingsongModel.KS_S20 to 30, KingsongModel.KS_S22 to 30,
            KingsongModel.KS_F18P to 36, KingsongModel.KS_F22P to 42,
        )
        assertEquals(KingsongModel.entries.size, was.size)
        for ((model, cells) in was) assertEquals(model.name, cells, model.cellsSeries)
    }

    @Test
    fun `Veteran keeps the counts its BMS split relies on`() {
        assertEquals(24, VeteranModel.SHERMAN.seriesCells)
        assertEquals(32, VeteranModel.PATTON.seriesCells)
        assertEquals(36, VeteranModel.NOSFET_AEON.seriesCells)
    }

    @Test
    fun `a wheel with no confirmed pack voltage asks the rider instead`() {
        assertNull(NinebotModel.MINI.nominalVoltage)
        assertNull(NinebotModel.MINI_PRO.nominalVoltage)
    }
}
