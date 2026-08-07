package com.eried.eucplanet.ui.settings

import com.eried.eucplanet.data.model.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard (CONVENTIONS rule 13) for the dashboard metric PICKER.
 *
 * The picker's source list [KNOWN_DASHBOARD_METRICS] is hand-maintained and
 * SEPARATE from [MetricCatalog]. A metric fully wired into the catalog but left
 * out of this list is invisible - it can never be added to the dashboard. That
 * exact gap silently hid TRIP_METER, PHASE_CURRENT, and EXTERNAL_GPS_BATTERY
 * (each fully built but un-selectable until someone noticed). This test fails
 * the build the moment a catalog metric isn't selectable, so it can't happen
 * again.
 */
class DashboardMetricPickerCoverageTest {

    private val catalogKeys: List<String> = MetricCatalog.all.map { it.key }

    @Test
    fun everyCatalogMetricIsSelectableInThePicker() {
        val missing = catalogKeys.filterNot { it in KNOWN_DASHBOARD_METRICS }
        assertTrue(
            "MetricCatalog metrics missing from KNOWN_DASHBOARD_METRICS, so a rider " +
                "can't add them to the dashboard - add them to the list: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun pickerHasNoStaleOrTypoEntries() {
        val catalog = catalogKeys.toSet()
        val stale = KNOWN_DASHBOARD_METRICS.filterNot {
            it in catalog || it in DASHBOARD_METRIC_ALIASES
        }
        assertTrue(
            "KNOWN_DASHBOARD_METRICS entries that are neither a MetricCatalog key nor a " +
                "documented alias in DASHBOARD_METRIC_ALIASES (typo or stale?): $stale",
            stale.isEmpty()
        )
    }

    @Test
    fun pickerListHasNoDuplicates() {
        val dupes = KNOWN_DASHBOARD_METRICS.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        assertEquals("Duplicate keys in KNOWN_DASHBOARD_METRICS: $dupes", emptySet<String>(), dupes)
    }
}
