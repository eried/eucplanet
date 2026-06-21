package com.eried.eucplanet.ble

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Validates the scan wheel-filter (issue 3) — real wheel names pass, common
 *  non-wheel BLE peripherals are filtered out. */
class WheelFilterTest {
    @Test fun recognizesRealWheels() {
        listOf(
            "KS-S22", "S22 PRO", "S18", "KingSong-16X",
            "Sherman", "Patton", "Lynx", "Abrams",
            "Begode_Master", "GotWay_RS", "Master_V3", "MSP", "Mten4",
            "Adventure-V14-50S", "P6-Pro", "InMotion V11", "V10F", "V8F",
            "Ninebot Z10", "Segway-Z6",
        ).forEach { assertTrue(isLikelyWheel(it), "should be detected as a wheel: $it") }
    }

    @Test fun filtersOutNonWheels() {
        listOf(
            "AirPods Pro", "MacBook", "Erwin's iPhone", "JBL Speaker",
            "Galaxy Buds", "TV", "Mi Band", "", null,
        ).forEach { assertFalse(isLikelyWheel(it), "should NOT be detected as a wheel: $it") }
    }
}
