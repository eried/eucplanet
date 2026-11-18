package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.data.model.GeoPoint
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real BRouter over a real tile. Runs only when BROUTER_TILES names a
 * folder with E10_N55.rd5 and lookups.dat (65 MB, not in the repo).
 */
class BrouterRealTileTest {

    @Test fun osloToSandvika_routesWithTurns() {
        val dir = System.getenv("BROUTER_TILES")?.let(::File)
        assumeTrue(dir != null && File(dir, "E10_N55.rd5").isFile)
        val profiles = File(dir, "profiles").apply { mkdirs() }
        File("src/main/assets/brouter/wheel.brf").copyTo(File(profiles, "wheel.brf"), overwrite = true)
        File(dir, "lookups.dat").copyTo(File(profiles, "lookups.dat"), overwrite = true)
        val req = OfflineRouter.Request(
            profile = "wheel",
            params = emptyMap(),
            headingDeg = null,
            points = listOf(GeoPoint(59.9139, 10.7522), GeoPoint(59.8890, 10.5240)),
        )
        val t0 = System.currentTimeMillis()
        val r = OfflineRouter.runBrouter(dir!!, profiles, req, 60_000)
        val ms = System.currentTimeMillis() - t0
        println("BRouter: ${r?.points?.size} points, ${r?.distanceM} m, ${r?.hints?.size} hints in $ms ms")
        r?.hints?.take(8)?.forEach { println("  hint cmd=${it.cmd} idx=${it.index} exit=${it.exit}") }
        assertTrue(r != null && r.distanceM in 12_000.0..25_000.0)
        assertTrue(r!!.hints.isNotEmpty())
    }
}
