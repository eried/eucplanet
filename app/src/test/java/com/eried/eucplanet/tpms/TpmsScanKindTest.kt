package com.eried.eucplanet.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A background monitor must never present itself as a search.
 *
 * The scanner opens a scan for two different reasons: the rider pressed Scan,
 * or a paired cap is being watched. Only the first shows as "scanning" and
 * only the first gives up after a while.
 *
 * That distinction was carried in a mutable field which the opening code read
 * before it had been assigned, so a monitor announced itself to the UI as a
 * search and armed the give-up window: a rider opened settings to find it
 * already scanning, and Stop handed the radio to a monitor that immediately
 * did the same thing again. Nothing could stop it.
 *
 * Reads the source because the branch lives inside a BLE callback that a unit
 * test cannot reach. Crude, but it fails for the reason a human would.
 */
class TpmsScanKindTest {

    private val source: String by lazy {
        val f = File("src/main/java/com/eried/eucplanet/tpms/TpmsScanner.kt")
        assertTrue("TpmsScanner.kt not found at ${f.absolutePath}", f.exists())
        f.readText()
    }

    @Test fun `the kind of scan is passed in, not read off a field`() {
        assertTrue(
            "openScan must take the scan kind as a parameter",
            Regex("""private fun openScan\([^)]*asMonitor: Boolean""").containsMatchIn(source),
        )
    }

    @Test fun `the UI state and the give-up window follow the parameter`() {
        // Reading `monitoring` here is the bug: it is assigned by this very
        // call, so a monitor read the value it was about to overwrite.
        val body = source.substringAfter("private fun openScan").substringBefore("\n    /**\n     * A scan that ends")
        assertTrue("_scanning must follow asMonitor", body.contains("_scanning.value = !asMonitor"))
        assertTrue("the window must follow asMonitor", body.contains("if (!asMonitor) startWindow()"))
        assertEquals(
            "openScan still decides from the mutable flag",
            0,
            Regex("""_scanning\.value = !monitoring""").findAll(body).count(),
        )
    }

    @Test fun `startMonitoring opens a monitor and start opens a search`() {
        assertTrue(
            "startMonitoring must open a monitor",
            source.contains("openScan(lowPower = true, asMonitor = true)"),
        )
        assertTrue(
            "start must open a search",
            source.contains("openScan(lowPower = false, asMonitor = false)"),
        )
    }
}
