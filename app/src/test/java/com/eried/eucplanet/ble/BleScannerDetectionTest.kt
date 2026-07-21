package com.eried.eucplanet.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the BLE scan filter ([isLikelyWheelName]): every supported wheel family
 * must stay recognised from its advertised name, and common non-wheel devices must
 * be ignored. Pure logic, no Bluetooth hardware, so it runs as a normal JVM unit
 * test. If a refactor breaks scanning, this fails instead of the rider just seeing
 * an empty scan list.
 */
class BleScannerDetectionTest {

    private val knownWheels = listOf(
        // InMotion V2
        "Adventure-1234", "P6-5678", "InMotionV12", "V11-ABC", "V13Pro-9", "V12HS-1",
        // InMotion V1 legacy
        "IM8123", "L6-22", "Lively-1", "Glide3", "Solowheel-X",
        // InMotion V10 family, including the lowercase-m "Inmotion-" branding
        // and the bare "V10F-…" advert form (regression: case-sensitive prefix
        // used to drop "Inmotion-V10F" so the wheel never appeared in scan).
        "V10F-1234", "V10-5678", "Inmotion-V10F", "InMotion V10F", "inmotion-v10",
        // KingSong
        "KS-S22", "KS S18", "KingSong18XL", "S22-001", "S20", "S18", "F22P", "F18P-3",
        // Begode / Gotway
        "Gotway_MSX", "Begode_RS", "Master_V3", "RS_19", "RS-19", "EX_30", "EX.30",
        "EX2", "MSP_C30", "MSX_100", "Mten4", "MCM5", "Hero-1", "T3", "T4",
        // Veteran / LeaperKim
        "Sherman-Max", "Patton", "Abrams", "LeaperKim-Lynx", "Lynx S", "LK20712",
        "LK19957", "Oryx", "Nosfet",
        // Ninebot / Segway
        "Ninebot Z10", "Segway-Z6", "ZN1234", "MiniPLUS-9", "Mini Plus 1",
    )

    private val nonWheels = listOf(
        "", "iPhone", "Galaxy Buds", "Mi Band 6", "JBL Flip 5", "Tile", "Bose QC",
        "Forerunner 945", "Random Device", "AirPods Pro", "Keyboard K380",
    )

    @Test fun `every supported wheel family is recognised`() {
        for (name in knownWheels) {
            assertTrue("scan filter should recognise wheel: '$name'", isLikelyWheelName(name))
        }
    }

    @Test fun `common non-wheel devices are ignored`() {
        for (name in nonWheels) {
            assertFalse("scan filter should ignore non-wheel: '$name'", isLikelyWheelName(name))
        }
    }
}
