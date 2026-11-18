package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The monitor asks the radio for every paired cap; a search hears everything. */
class TpmsMonitorFilterTest {

    private val a = "AA:BB:CC:DD:EE:01"
    private val b = "AA:BB:CC:DD:EE:02"
    private val two = listOf(TpmsRepository.SensorState(a), TpmsRepository.SensorState(b))

    @Test fun `two paired caps give two addresses`() {
        assertEquals(listOf(a, b), TpmsScanner.monitorFilterAddresses(asMonitor = true, sensors = two))
    }

    @Test fun `nothing paired gives no filter`() {
        assertNull(TpmsScanner.monitorFilterAddresses(asMonitor = true, sensors = emptyList()))
    }

    @Test fun `a search never filters`() {
        assertNull(TpmsScanner.monitorFilterAddresses(asMonitor = false, sensors = two))
    }
}
