package com.eried.eucplanet.metrics

import com.eried.eucplanet.data.model.AlarmMetric
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.ui.studio.StudioMetric
import com.eried.eucplanet.util.TripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Motor torque and phase amps are first-class metrics everywhere at once.
 *
 * Riders read phase amps to judge how close a wheel is to its limit before
 * pedal dip; torque is the next best where a family cannot provide phase.
 * The registries this feature touches all have to move together - recorder
 * column, trip reader, alarms, widgets, studio - so this suite pins each
 * one, the same way the tile registry is pinned.
 */
class TorquePhaseMetricsTest {

    private fun src(path: String) = File("src/main/java/com/eried/eucplanet/$path").readText()

    // --- CSV schema ---------------------------------------------------------

    private val header = "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude," +
        "Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra"

    @Test fun `the recorder writes both columns, before Extra`() {
        assertTrue(src("util/CsvWriter.kt").contains(header))
        // Extra must stay the last column: it is the free-text event channel.
        assertTrue(header.endsWith("Torque,Phase current,Extra"))
    }

    @Test fun `the recorder keeps the source resolution`() {
        // A tester noticed the odometer column rounded to 0.1 km when the
        // wheel resolves 0.001, which put up to ~0.1 km of error into the
        // derived trip distance (metricsFrom sums that column's deltas).
        // Wheel-sourced columns write hundredths, the odometer thousandths.
        assertTrue(src("util/CsvWriter.kt")
            .contains("\"%s,%.2f,%.2f,%.2f,%d,%.1f,%.6f,%.6f,%.3f,%.1f,%.2f,%.2f,%.3f,%.3f,%.3f,%.2f,%.2f,%s\""))
    }

    @Test fun `the euc-world import emits the same canonical header`() {
        val vm = src("ui/recording/RecordingViewModel.kt")
        assertTrue(vm.contains(header))
        // Its row builder pads the columns it cannot fill, keeping Extra last.
        assertTrue(vm.contains("\",,,,,,,\""))
    }

    @Test fun `the trip reader resolves the columns tolerantly`() {
        val h = header.lowercase().split(",")
        assertEquals(15, TripCsv.Columns.torque(h))
        assertEquals(16, TripCsv.Columns.phaseCurrent(h))
        // Foreign exports use underscores.
        assertEquals(0, TripCsv.Columns.phaseCurrent(listOf("phase_current")))
        // Old files simply lack them.
        assertEquals(-1, TripCsv.Columns.torque(listOf("date", "speed")))
    }

    // --- BLE sources --------------------------------------------------------

    @Test fun `families whose wire current is phase current say so`() {
        assertTrue("Begode frame A", src("ble/BegodeParser.kt").contains("phaseCurrent = phaseCurrent"))
        assertTrue("Begode extended", src("ble/BegodeParser.kt").contains("phaseCurrent = battCurrent"))
        assertTrue("Veteran", src("ble/VeteranParser.kt").contains("phaseCurrent = current"))
    }

    // --- Trip details -------------------------------------------------------

    @Test fun `trip details charts and tiles exist and gate on real data`() {
        val td = src("ui/recording/TripDetailScreen.kt")
        assertTrue(td.contains("\"torque\", \"phaseCurrent\","))
        // Both graphs ship OFF: they are opt-in extras like the smoothed
        // variants, so the default chart list does not grow.
        val extras = td.substringAfter("EXTRA_CHART_KEYS = setOf(").substringBefore(")")
        assertTrue("torque chart is on by default", extras.contains("\"torque\""))
        assertTrue("phase chart is on by default", extras.contains("\"phaseCurrent\""))
        assertTrue(td.contains("\"torque\" in extraCharts &&"))
        assertTrue(td.contains("\"phaseCurrent\" in extraCharts &&"))
        // All-zero columns (families that never report) must not draw charts.
        assertTrue(td.contains("!it.torque.isNaN() && it.torque != 0f"))
        assertTrue(td.contains("!it.phaseCurrent.isNaN() && it.phaseCurrent != 0f"))
        assertTrue(td.contains("\"maxTorque\", \"maxPhaseCurrent\","))
    }

    // --- Alarms -------------------------------------------------------------

    @Test fun `both metrics can drive a custom alarm`() {
        assertEquals("Nm", AlarmMetric.TORQUE.unit)
        assertEquals("A", AlarmMetric.PHASE_CURRENT.unit)
        val engine = src("service/AlarmEngine.kt")
        assertTrue(engine.contains("AlarmMetric.TORQUE -> data.torque.absoluteValue"))
        assertTrue(engine.contains("AlarmMetric.PHASE_CURRENT -> data.phaseCurrent.absoluteValue"))
    }

    // --- Widgets ------------------------------------------------------------

    @Test fun `the widget can show both`() {
        assertEquals(WidgetMetricType.TORQUE, WidgetMetricType.byKey("TORQUE"))
        assertEquals(WidgetMetricType.PHASE_CURRENT, WidgetMetricType.byKey("PHASE_CURRENT"))
    }

    // --- Studio / Phone HUD -------------------------------------------------

    @Test fun `studio overlays extract the real fields`() {
        val wd = WheelData(torque = 12.5f, phaseCurrent = 231f)
        assertEquals(12.5f, StudioMetric.TORQUE.extract(wd))
        assertEquals(231f, StudioMetric.PHASE_CURRENT.extract(wd))
    }

    // --- Localization -------------------------------------------------------

    @Test fun `every locale carries all the new strings`() {
        val keys = listOf(
            "alarm_metric_torque", "alarm_metric_phase_current", "alarm_metric_phase_current_voice",
            "recording_summary_max_torque", "recording_summary_max_phase_current",
            "recording_chart_torque", "recording_chart_phase_current",
            "widget_metric_torque", "widget_metric_phase_current",
            "studio_metric_torque",
        )
        val missing = File("src/main/res").listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .flatMap { dir ->
                val t = File(dir, "strings.xml").readText()
                keys.filter { !t.contains("name=\"$it\"") }.map { "${dir.name}/$it" }
            }
        assertTrue("missing: $missing", missing.isEmpty())
    }
}
