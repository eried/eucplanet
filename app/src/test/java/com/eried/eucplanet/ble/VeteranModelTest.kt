package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VeteranModelTest {

    @Test
    fun `NOSFET models resolve from advertised BLE names`() {
        assertEquals(VeteranModel.NOSFET_APEX, VeteranModel.fromReportedName("NOSFET Apex"))
        assertEquals(VeteranModel.NOSFET_APEX, VeteranModel.fromReportedName("nosfetapex"))
        assertEquals(VeteranModel.NOSFET_AERO, VeteranModel.fromReportedName("NOSFET Aero"))
        assertEquals(VeteranModel.NOSFET_AERO, VeteranModel.fromReportedName("nosfetaero"))
        assertEquals(VeteranModel.NOSFET_AEON, VeteranModel.fromReportedName("NOSFET Aeon"))
        assertEquals(VeteranModel.NOSFET_AEON, VeteranModel.fromReportedName("nosfetaeon"))
        assertEquals(VeteranModel.NOSFET_XENO, VeteranModel.fromReportedName("NOSFET Xeno"))
        assertEquals(VeteranModel.NOSFET_XENO, VeteranModel.fromReportedName("nosfetxeno"))
        assertEquals(VeteranModel.NOSFET_XENO, VeteranModel.fromReportedName("Xeno"))
        assertEquals(VeteranModel.NOSFET_XENO, VeteranModel.fromReportedName("xeno"))
    }

    @Test
    fun `NOSFET models resolve from mVer code`() {
        assertEquals(VeteranModel.NOSFET_APEX, VeteranModel.fromMVer(42))
        assertEquals(VeteranModel.NOSFET_AERO, VeteranModel.fromMVer(43))
        assertEquals(VeteranModel.NOSFET_AEON, VeteranModel.fromMVer(44))
        assertEquals(VeteranModel.NOSFET_XENO, VeteranModel.fromMVer(45))
        assertNull(VeteranModel.fromMVer(999))
    }

    @Test
    fun `NOSFET models have brandOverride set and correct cell counts`() {
        val nosfetModels = listOf(
            VeteranModel.NOSFET_APEX,
            VeteranModel.NOSFET_AERO,
            VeteranModel.NOSFET_AEON,
            VeteranModel.NOSFET_XENO
        )
        for (m in nosfetModels) {
            assertEquals("NOSFET", m.brandOverride)
        }
        assertEquals(30, VeteranModel.NOSFET_AERO.seriesCells)
        assertEquals(30, VeteranModel.NOSFET_XENO.seriesCells)
        assertEquals(36, VeteranModel.NOSFET_APEX.seriesCells)
        assertEquals(36, VeteranModel.NOSFET_AEON.seriesCells)
    }
}
