package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the connect-time protocol routing ([wheelFamilyForName]): once a wheel is
 * chosen and we open a GATT connection, the right protocol family must drive it, or
 * the rider connects but sees no data (or the wrong commands go out). Pure logic, no
 * Bluetooth hardware, so it runs as a normal JVM unit test. Complements
 * [BleScannerDetectionTest] (which guards that the same wheels survive the scan
 * filter in the first place).
 */
class CompositeWheelAdapterRoutingTest {

    // Representative advertised names per family. Each must route to the listed
    // family; if a future refactor mis-routes any, this fails loudly instead of the
    // rider silently getting a dead connection.
    private val expected = mapOf(
        WheelFamily.INMOTION_V1 to listOf(
            "V5-x", "V8", "V10F", "L6-22", "Lively1", "Glide3", "Solowheel-X",
            "IM8123", "inmotion-v8", "inmotion-v10f",
        ),
        WheelFamily.INMOTION_V2 to listOf(
            // V11+ share the V<digits> shape but are V2; plus the explicit V2 names.
            "V11-ABC", "V12HS-1", "V13Pro-9", "InMotionV12", "Adventure-1234", "P6-5678",
        ),
        WheelFamily.KINGSONG to listOf(
            "KS-S22", "KS S18", "KingSong18XL", "S22-001", "S20", "S18-x", "F18P", "F22P",
        ),
        WheelFamily.VETERAN to listOf(
            "Sherman-Max", "Patton", "Abrams", "LeaperKim-Lynx", "Lynx S",
            "LK20712", "LK19957", "Oryx", "Nosfet",
        ),
        WheelFamily.NINEBOT to listOf(
            "Ninebot Z10", "Segway-Z6", "ZN1234", "MiniPLUS-9", "Mini Plus 1",
        ),
        WheelFamily.BEGODE to listOf(
            "Gotway_MSX", "Begode_RS", "Master_V3", "RS_19", "RS-19", "EX_30", "EX.30",
            "EX2", "MSP_C30", "MSX_100", "Mten4", "MCM5", "Hero-1", "T3", "T4",
        ),
    )

    @Test fun `each wheel name routes to its protocol family`() {
        for ((family, names) in expected) {
            for (name in names) {
                assertEquals("name '$name' should route to $family", family, wheelFamilyForName(name))
            }
        }
    }

    @Test fun `blank or unknown names fall back to the InMotion V2 default`() {
        // The cold-connect default: a null/blank name, or anything we don't recognise,
        // must not crash the router -- it lands on the V2 adapter (the historical default).
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName(null))
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName(""))
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("   "))
        assertEquals(WheelFamily.INMOTION_V2, wheelFamilyForName("TotallyUnknownDevice"))
    }
}
