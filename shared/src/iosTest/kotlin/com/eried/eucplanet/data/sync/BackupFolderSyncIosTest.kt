package com.eried.eucplanet.data.sync

import com.eried.eucplanet.app.IosBackupFolders
import com.eried.eucplanet.app.IosPathFolder
import com.eried.eucplanet.app.IosTripFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup folder on the iPhone's own storage: [IosPathFolder] over a real
 * directory, opened through the security-scoped bookmark a Files pick makes,
 * with the real Documents-style trips folder ([IosTripFiles]), sharing the
 * folder with a phone that runs Android's side in memory.
 */
@OptIn(ExperimentalForeignApi::class)
class BackupFolderSyncIosTest {
    private val root = NSTemporaryDirectory() + "backup-folder-sync-test"
    private val folderPath = "$root/EUC Planet backup"
    private val tripsDir = "$root/trips"

    @AfterTest fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun text(path: String) = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    private val csv =
        "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra\n" +
            "01.06.2026 20:24:31.204,0.00,100.80,31.00,92,12.0,59.911491,10.757933,1234.500,0.0,0.00,0.00,0.000,0.000,0.000,0.00,0.00,trip.name=Coast road\n"

    @Test fun thePathFolderBehavesAsTheSharedSyncExpects() {
        cleanUp()
        NSFileManager.defaultManager.createDirectoryAtPath(folderPath, true, null, null)
        val f = IosPathFolder(folderPath)
        assertEquals(emptyList(), f.list())
        assertNull(f.list("trips"))
        // No folder, no file: the parent must exist.
        assertFalse(f.write("trips/a.csv", "x".encodeToByteArray()))
        assertTrue(f.ensureDirectory("trips/archive"))
        assertTrue(f.write("trips/a.csv", "abc".encodeToByteArray()))
        assertTrue(f.write("trips/a.csv", "replaced".encodeToByteArray()))
        assertEquals("replaced", f.read("trips/a.csv")?.decodeToString())
        assertEquals("rep", f.readHead("trips/a.csv", 3)?.decodeToString())
        val entry = f.list("trips")!!.single { it.name == "a.csv" }
        assertEquals(8L, entry.size)
        assertFalse(entry.isDirectory)
        assertTrue(entry.lastModified > 0)
        assertTrue(f.list("trips")!!.single { it.name == "archive" }.isDirectory)
        assertNull(f.read("trips/archive"))
        assertTrue(f.moveInto("trips/a.csv", "trips/archive"))
        assertEquals("replaced", f.read("trips/archive/a.csv")?.decodeToString())
        assertTrue(f.write("trips/a.csv", "again".encodeToByteArray()))
        // A move never lands on a name that is taken.
        assertFalse(f.moveInto("trips/a.csv", "trips/archive"))
        assertTrue(f.delete("trips/a.csv"))
        assertFalse(f.delete("trips/a.csv"))
        assertFalse(f.ensureDirectory("trips/archive/a.csv"))
    }

    @Test fun aBookmarkOpensTheFolderAndAMissingFolderIsUnreachable() {
        cleanUp()
        val uri = IosBackupFolders.bookmarkForPath(folderPath)
        assertNotNull(uri)
        assertTrue(uri.startsWith(IosBackupFolders.PREFIX))
        val folder = IosBackupFolders.open(uri)
        assertNotNull(folder)
        assertTrue(folder.write("probe.txt", "ok".encodeToByteArray()))
        assertEquals("ok", text("$folderPath/probe.txt"))
        // An Android tree URI is never taken for an iPhone folder.
        assertNull(IosBackupFolders.open("content://com.android.externalstorage.documents/tree/primary%3ADocuments"))
        NSFileManager.defaultManager.removeItemAtPath(folderPath, null)
        assertNull(IosBackupFolders.open(uri))
    }

    @Test fun anAndroidFolderIsReadAndWrittenByTheIphone(): Unit = runBlocking {
        cleanUp()
        NSFileManager.defaultManager.createDirectoryAtPath(folderPath, true, null, null)
        val shared = IosPathFolder(folderPath)
        // Android's side in memory, writing the same directory.
        val android = FolderTestPhone(shared).apply { pickFolder() }
        android.record("trip_android.csv", csv, status = 1)
        assertTrue(android.sync.uploadPass())
        android.sync.ensureRiderIdFile("rider-android")
        assertTrue(android.sync.backupSettings())
        assertEquals(BackupOutcome.Saved, android.sync.backupSettingsAs("alpine", overwrite = false))

        val iphone = FolderTestPhone(shared, filesFactory = { IosTripFiles { tripsDir } }).apply { pickFolder() }
        val ui = object : DropboxSyncUi {
            var result: SyncResult? = null
            override suspend fun askConflict(count: Int, firstSettings: Boolean) = SyncChoice.IGNORE
            override fun progress(done: Int, total: Int) = Unit
            override fun result(result: SyncResult) { this.result = result }
        }
        iphone.sync.runSync(ui)
        assertEquals(SyncResult.Finished(1), ui.result)
        assertEquals(csv, text("$tripsDir/trip_android.csv"))
        assertEquals("Coast road", (iphone.dao as MemoryTripDao).byName("trip_android.csv")?.customName)
        assertEquals("rider-android", iphone.sync.findRestorableRider()?.storeId)
        assertEquals(
            listOf("eucplanet_settings.json", "eucplanet_settings-alpine.json"),
            iphone.sync.listSettingsBackups().map { it.fileName },
        )
        assertTrue(iphone.sync.restoreSettingsFrom("eucplanet_settings-alpine.json"))

        // The iPhone's trip goes into the same trips/, and its archive beside it.
        iphone.record("trip_iphone.csv", csv, status = 1)
        assertTrue(iphone.sync.uploadPass())
        assertEquals(csv, text("$folderPath/trips/trip_iphone.csv"))
        assertTrue(iphone.sync.archiveInBackupFolder(iphone.settings.get(), "trip_iphone.csv"))
        assertEquals(csv, text("$folderPath/trips/archive/trip_iphone.csv"))
        assertNull(text("$folderPath/trips/trip_iphone.csv"))

        // Themes and presets land where Android's stores look.
        assertTrue(FolderThemes { shared }.save("Night", com.eried.eucplanet.ui.theme.BuiltInThemes.dark.colors))
        assertTrue(FolderPresets { shared }.save("Race", com.eried.eucplanet.hud.protocol.OverlayPreset()))
        assertNotNull(text("$folderPath/themes/Night.json"))
        assertNotNull(text("$folderPath/overlays/Race.json"))
    }
}
