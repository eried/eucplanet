package com.eried.eucplanet.app

import com.eried.eucplanet.hud.protocol.OverlayPreset
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The studio's iPhone side: the presets folder and the rotation reading. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosStudioPortsTest {
    private val dir = NSTemporaryDirectory() + "studio_" + Uuid.random()

    @AfterTest fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    @Test fun aPresetSavesListsLoadsAndDeletes() = runBlocking {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val presets = IosOverlayPresets(dir)
        assertTrue(presets.presetFolderAvailable())
        assertTrue(presets.savePreset("Night ride", OverlayPreset()))
        assertEquals(listOf("Night ride"), presets.listPresets())
        assertEquals("Night ride", presets.loadPreset("Night ride")?.name)
        // The folder is what Dropbox's /overlays mirrors.
        assertEquals(setOf("Night ride.json"), presets.jsonFiles()?.keys)
        assertTrue(presets.deletePreset("Night ride"))
        assertEquals(emptyList(), presets.listPresets())
        assertNull(presets.loadPreset("Night ride"))
    }

    @Test fun aPresetNameIsCleanedAsOnAndroid() = runBlocking {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val presets = IosOverlayPresets(dir)
        // SyncManager.sanitizeBackupName: letters, digits, _ - and single spaces.
        assertTrue(presets.savePreset("  a/b:c   d!  ", OverlayPreset()))
        assertEquals(listOf("abc d"), presets.listPresets())
        assertFalse(presets.savePreset("///", OverlayPreset()))
    }

    @Test fun theDraftRoundTrips() = runBlocking {
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val presets = IosOverlayPresets(dir)
        presets.saveDraft(OverlayPreset(name = "draft"))
        assertNotNull(presets.loadDraft())
        assertEquals("draft", presets.loadDraft().name)
    }

    @Test fun rotationReadsAsOrientationEventListener() {
        // Core Motion's g: upright portrait has gravity along -y.
        assertEquals(0, orientationOf(0f, -1f, 0f))
        // Right edge up (left side down), Android's 270.
        assertEquals(270, orientationOf(-1f, 0f, 0f))
        assertEquals(90, orientationOf(1f, 0f, 0f))
        assertEquals(180, orientationOf(0f, 1f, 0f))
        // Flat on a table: unknown.
        assertNull(orientationOf(0f, -0.1f, -1f))
    }
}
