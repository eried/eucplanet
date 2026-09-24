package com.eried.eucplanet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Application.onCreate is a list of one-line starts, and nothing else in the
 * app notices when one goes missing: the feature simply never wakes up. The
 * watch-map merge (PR #25) replaced `flicManager.initialize()` with the tile
 * cache start, and Flic buttons went dead with no scan, no forget and no
 * presses, and not one line in the log. This pins every start that has to be
 * in onCreate, so a merge cannot drop one again without a red test.
 */
class AppStartupTest {

    @Test fun `every process-wide start is still called from onCreate`() {
        // Comments stripped first: a commented-out start is a missing start.
        val src = stripComments(File("src/main/java/com/eried/eucplanet/EucPlanetApp.kt").readText())
        val onCreate = src.substringAfter("override fun onCreate()")
        for (call in listOf(
            "CrashHandler.install(this)",
            "flicManager.initialize()",
            "MapTileCache.start(settingsRepository)",
            "wearBridge.start()",
            "garminBridge.start()",
            "amazfitBridge.start()",
            "hudServer.hashCode()",
            "syncManager.reconcilePendingTripUploads()",
            "syncManager.reconcilePendingEucStatsUploads()",
            "syncManager.reconcilePendingDropboxSync()",
            "syncManager.startPendingUploadWatcher()",
        )) {
            assertTrue("$call is no longer called from EucPlanetApp.onCreate", onCreate.contains(call))
        }
    }
}
