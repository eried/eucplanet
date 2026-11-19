package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.ui.theme.BuiltInThemes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup folder end to end, against [MemoryBackupFolder]: the shared
 * passes Android's SyncManager and the iPhone run, with the names and
 * subfolders an Android phone writes. Runs on the JVM and on the iOS
 * simulator, so both phones read and write one folder by the same rules.
 */
class BackupFolderSyncTest {

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun csv(name: String? = null, tail: String = "") = buildString {
        append("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra\n")
        append("01.06.2026 20:24:31.204,0.00,100.80,31.00,92,12.0,59.911491,10.757933,1234.500,0.0,0.00,0.00,0.000,0.000,0.000,0.00,0.00,wheel.name=Master_1234\n")
        if (name != null) {
            append("01.06.2026 20:24:32.204,12.50,100.70,31.00,92,12.0,59.911591,10.757933,1234.510,12.4,3.10,12.00,0.010,0.000,0.000,0.00,0.00,trip.name=$name\n")
        }
        append("01.06.2026 20:24:33.204,18.20$tail,100.60,31.50,92,12.1,59.911691,10.757933,1234.520,18.1,5.20,20.00,0.020,0.000,0.000,0.00,0.00,\n")
    }

    private class Ui(var answer: SyncChoice = SyncChoice.IGNORE) : DropboxSyncUi {
        val asked = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()
        var result: SyncResult? = null
        override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
            asked += count
            return answer
        }
        override fun progress(done: Int, total: Int) { progress += done to total }
        override fun result(result: SyncResult) { this.result = result }
    }

    // --- the folder itself ---------------------------------------------------

    @Test fun withoutAFolderNothingIsWrittenAndSyncSaysSo() = run {
        val phone = FolderTestPhone()
        assertNull(phone.sync.folder(phone.settings.get()))
        assertFalse(phone.sync.backupSettings())
        assertEquals(RiderFileResult.SKIPPED, phone.sync.ensureRiderIdFile("abc"))
        val ui = Ui()
        phone.sync.runSync(ui)
        assertEquals(SyncResult.NoFolder, ui.result)
        assertTrue(phone.sync.uploadPass())
    }

    @Test fun aFolderNoLongerReachableReadsAsNoFolder() = run {
        val phone = FolderTestPhone()
        phone.pickFolder()
        assertEquals("Memory folder", phone.sync.displayName(phone.settings.get()))
        phone.reachable = false
        assertNull(phone.sync.displayName(phone.settings.get()))
        assertNull(phone.sync.listFolderTripNames(phone.settings.get()))
        val ui = Ui()
        phone.sync.runSync(ui)
        assertEquals(SyncResult.NoFolder, ui.result)
    }

    @Test fun removingTheFolderKeepsTheLeaderboardOnlyWhereTheIdLivesElsewhere() = run {
        val android = FolderTestPhone().apply { pickFolder() }
        android.settings.update { it.copy(onlineUploadEnabled = true, folderConflictCount = 2) }
        android.sync.forgetFolder(riderIdInFolder = true)
        android.settings.get().let {
            assertNull(it.syncFolderUri)
            assertFalse(it.onlineUploadEnabled)
            assertEquals(0, it.folderConflictCount)
        }
        assertEquals(1, android.host.cancels)

        val iphone = FolderTestPhone().apply { pickFolder() }
        iphone.settings.update { it.copy(onlineUploadEnabled = true) }
        iphone.sync.forgetFolder(riderIdInFolder = false)
        assertTrue(iphone.settings.get().onlineUploadEnabled)
    }

    // --- settings backups ----------------------------------------------------

    @Test fun theSettingsBackupAndNamedCopiesAreAndroidsFiles() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.alarms.rules = listOf(AlarmRule(name = "Hot"))

        assertTrue(phone.sync.backupSettings())
        assertEquals(BackupOutcome.Saved, phone.sync.backupSettingsAs("Before trip", overwrite = false))
        assertEquals(BackupOutcome.Saved, phone.sync.backupSettingsAs("alpine", overwrite = false))
        // Asking first: a named copy is never replaced unasked.
        assertEquals(BackupOutcome.AlreadyExists, phone.sync.backupSettingsAs("alpine", overwrite = false))
        assertEquals(BackupOutcome.Saved, phone.sync.backupSettingsAs("alpine", overwrite = true))

        val main = folder.text("eucplanet_settings.json")!!
        // The stamp leads the file, the alarm rules travel inside it.
        assertTrue(main.trimStart().startsWith("{\n  \"backupAt\":"), main.take(40))
        assertTrue(main.contains("\"alarms\""))
        assertTrue(main.contains("Hot"))
        assertNotNull(folder.text("eucplanet_settings-Before trip.json"))

        val list = phone.sync.listSettingsBackups()
        assertEquals(
            listOf("eucplanet_settings.json", "eucplanet_settings-alpine.json", "eucplanet_settings-Before trip.json"),
            list.map { it.fileName },
        )
        assertEquals(listOf(null, "alpine", "Before trip"), list.map { it.label })
        assertTrue(list.all { it.writtenAt != null })
    }

    @Test fun aBackupFromBeforeStampsDatesFromTheFolder() = run {
        val folder = MemoryBackupFolder(clock = { 1_700_000_000_000L })
        folder.put("eucplanet_settings.json", "{\n  \"unitSpeed\": \"kmh\"\n}")
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        assertEquals(1_700_000_000_000L, phone.sync.listSettingsBackups().single().writtenAt)
    }

    @Test fun restoringANamedCopyBringsItsSettingsAndAlarmsBack() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.settings.update { it.copy(advanced = it.advanced.copy(tripFinalizeGraceMs = 20_000)) }
        phone.alarms.rules = listOf(AlarmRule(name = "Saved rule"))
        assertEquals(BackupOutcome.Saved, phone.sync.backupSettingsAs("mine", overwrite = false))

        phone.settings.update { it.copy(advanced = it.advanced.copy(tripFinalizeGraceMs = 40_000)) }
        phone.alarms.rules = emptyList()
        assertTrue(phone.sync.restoreSettingsFrom("eucplanet_settings-mine.json"))
        assertEquals(20_000, phone.settings.get().tripFinalizeGraceMs)
        assertEquals(listOf("Saved rule"), phone.alarms.rules.map { it.name })
        // The phone keeps its own folder.
        assertEquals(FolderTestPhone.URI, phone.settings.get().syncFolderUri)
        assertFalse(phone.sync.restoreSettingsFrom("eucplanet_settings-missing.json"))
    }

    @Test fun theSnapshotBeforeARestoreIsANamedCopy() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        assertTrue(phone.sync.snapshotBeforeRestore())
        val name = folder.names("").single()
        assertTrue(Regex("""eucplanet_settings-before-restore-\d{8}-\d{4}\.json""").matches(name), name)
    }

    @Test fun factoryDefaultsClearTheRulesAndKeepTheFolder() = run {
        val phone = FolderTestPhone().apply { pickFolder() }
        phone.alarms.rules = listOf(AlarmRule(name = "x"))
        phone.settings.update { it.copy(advanced = it.advanced.copy(tripFinalizeGraceMs = 40_000)) }
        assertTrue(phone.sync.restoreFactoryDefaults())
        assertTrue(phone.alarms.rules.isEmpty())
        assertEquals(15_000, phone.settings.get().tripFinalizeGraceMs)
        assertEquals(FolderTestPhone.URI, phone.settings.get().syncFolderUri)
    }

    @Test fun backupNamesAreCleanedAsOnAndroid() {
        assertEquals("abc d", BackupFolderSync.sanitizeBackupName("  a/b:c   d!  "))
        assertNull(BackupFolderSync.sanitizeBackupName("///"))
        assertEquals(32, BackupFolderSync.sanitizeBackupName("x".repeat(40))!!.length)
        assertEquals("eucplanet_settings-x.json", BackupFolderSync.buildBackupFileName("x"))
    }

    // --- the rider id ----------------------------------------------------------

    @Test fun theRiderIdFileIsWrittenReadAndNeverClobbered() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        assertNull(phone.sync.findRestorableRider())
        assertEquals(RiderFileResult.WROTE, phone.sync.ensureRiderIdFile("rider-1"))
        assertEquals("rider-1", folder.text("eucstats_riderid.txt"))
        assertEquals(RiderFileResult.ALREADY_PRESENT, phone.sync.ensureRiderIdFile("rider-1"))
        assertEquals(RiderFileResult.MISMATCH, phone.sync.ensureRiderIdFile("rider-2"))
        assertEquals("rider-1", folder.text("eucstats_riderid.txt"))
        assertEquals(RestorableRider("eucstats_riderid.txt", "rider-1"), phone.sync.findRestorableRider())

        // A file written by hand, with a newline, reads the same.
        folder.put("eucstats_riderid.txt", "rider-3\n")
        assertEquals("rider-3", phone.sync.readRiderIdFile())
        assertTrue(phone.sync.writeRiderIdFile("rider-4"))
        assertEquals("rider-4", folder.text("eucstats_riderid.txt"))
        assertEquals(true, phone.sync.deleteRiderIdFile())
        assertEquals(false, phone.sync.deleteRiderIdFile())
        assertNull(phone.sync.readRiderIdFile())
    }

    // --- trips: the upload pass -------------------------------------------------

    @Test fun theUploadPassBacksUpQueuedTripsAndFillsGaps() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("trip_a.csv", csv("A"), status = 1)
        // Finished before the folder was set: never queued, still missing.
        phone.record("trip_b.csv", csv("B"), status = 2)
        // Not a trip of the folder's: a ride still recording.
        phone.dao.insert(com.eried.eucplanet.data.model.TripRecord(fileName = "open.csv", startTime = 1L))

        assertTrue(phone.sync.uploadPass())
        assertEquals(listOf("trip_a.csv", "trip_b.csv"), folder.names("trips").sorted())
        assertEquals(csv("A"), folder.text("trips/trip_a.csv"))
        val dao = phone.dao as MemoryTripDao
        assertEquals(2, dao.byName("trip_a.csv")!!.uploadStatus)
        assertEquals(2, dao.byName("trip_b.csv")!!.uploadStatus)
        assertTrue(phone.host.scheduled.isEmpty())
    }

    @Test fun aTripFromDropboxNeverOverwritesTheFolderCopy() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/trip_a.csv", "the folder's own version")
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("trip_a.csv", csv(), status = 4)
        assertTrue(phone.sync.uploadPass())
        assertEquals("the folder's own version", folder.text("trips/trip_a.csv"))
        // The two differ: the warning counts it.
        assertEquals(1, phone.settings.get().folderConflictCount)
    }

    @Test fun aCaseOnlyDifferenceIsTheSameTripNotACopy() = run {
        val folder = MemoryBackupFolder(renameClashes = true)
        folder.put("trips/Inmotion_V14.csv", "old")
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("inmotion_v14.csv", csv(), status = 1)
        assertTrue(phone.sync.uploadPass())
        // Replaced, under the app's spelling, with no "(1)" beside it.
        assertEquals(listOf("inmotion_v14.csv"), folder.names("trips"))
        assertEquals(csv(), folder.text("trips/inmotion_v14.csv"))
    }

    @Test fun aFailedUploadIsMarkedAndRetried() = run {
        val folder = object : BackupFolder by MemoryBackupFolder() {
            override fun write(path: String, bytes: ByteArray) = false
        }
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("trip_a.csv", csv(), status = 1)
        assertFalse(phone.sync.uploadPass())
        assertEquals(3, (phone.dao as MemoryTripDao).byName("trip_a.csv")!!.uploadStatus)
    }

    @Test fun renamedDuplicatesWithTheSameBytesAreRemoved() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/trip.csv", "same")
        folder.put("trips/trip (1).csv", "same")
        folder.put("trips/trip (2).csv", "different")
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        assertEquals(1, phone.sync.removeRenamedTripCopies(phone.settings.get()))
        assertEquals(listOf("trip (2).csv", "trip.csv"), folder.names("trips").sorted())
    }

    // --- trips: Sync all ----------------------------------------------------------

    @Test fun syncAllCopiesBothWaysAndAsksOnlyAboutRealConflicts() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/from_android.csv", csv("Coast road"))
        folder.put("trips/same.csv", csv())
        folder.put("trips/differs.csv", csv(tail = "9"))
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("same.csv", csv(), status = 2)
        phone.record("differs.csv", csv(), status = 2)
        phone.record("only_here.csv", csv(), status = 0)

        val ui = Ui(SyncChoice.IGNORE)
        phone.sync.runSync(ui)
        assertEquals(listOf(1), ui.asked)
        // Up: only_here; down: from_android. The conflict is left alone.
        assertEquals(SyncResult.Finished(2), ui.result)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), ui.progress)
        assertEquals(csv(), folder.text("trips/only_here.csv"))
        val dao = phone.dao as MemoryTripDao
        val down = dao.byName("from_android.csv")!!
        assertEquals("Coast road", down.customName)
        assertEquals(2, down.uploadStatus)
        assertEquals(csv("Coast road"), phone.files.file("from_android.csv").readBytes().decodeToString())
        assertEquals(csv(tail = "9"), folder.text("trips/differs.csv"))
        assertEquals(csv(), phone.files.file("differs.csv").readBytes().decodeToString())
        // Still differing, so the warning stays up after the pass.
        assertEquals(1, phone.settings.get().folderConflictCount)
    }

    @Test fun answeringFolderTakesTheFolderCopy() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/differs.csv", csv("Theirs", tail = "9"))
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("differs.csv", csv(), status = 2)
        val ui = Ui(SyncChoice.FOLDER)
        phone.sync.runSync(ui)
        assertEquals(SyncResult.Finished(1), ui.result)
        assertEquals(csv("Theirs", tail = "9"), phone.files.file("differs.csv").readBytes().decodeToString())
        assertEquals("Theirs", (phone.dao as MemoryTripDao).byName("differs.csv")!!.customName)
        assertEquals(0, phone.settings.get().folderConflictCount)
    }

    @Test fun cancellingTheQuestionMovesNothing() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/differs.csv", csv(tail = "9"))
        folder.put("trips/new.csv", csv())
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("differs.csv", csv(), status = 2)
        val ui = Ui(SyncChoice.CANCEL)
        phone.sync.runSync(ui)
        assertNull(ui.result)
        assertNull((phone.dao as MemoryTripDao).byName("new.csv"))
    }

    @Test fun anUpToDateFolderSaysSo() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/same.csv", csv())
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.record("same.csv", csv(), status = 2)
        val ui = Ui()
        phone.sync.runSync(ui)
        assertEquals(SyncResult.UpToDate, ui.result)
        assertTrue(ui.asked.isEmpty())
    }

    // --- one folder, two phones ---------------------------------------------------

    @Test fun anAndroidPhoneAndAnIphoneShareOneFolder() = run {
        val folder = MemoryBackupFolder()
        val android = FolderTestPhone(folder).apply { pickFolder() }
        val iphone = FolderTestPhone(folder).apply { pickFolder() }

        android.record("trip_android.csv", csv("Ridden on Android"), status = 1)
        assertTrue(android.sync.uploadPass())
        android.sync.ensureRiderIdFile("rider-android")
        assertTrue(android.sync.backupSettings())

        val ui = Ui()
        iphone.sync.runSync(ui)
        assertEquals(SyncResult.Finished(1), ui.result)
        assertEquals("Ridden on Android", (iphone.dao as MemoryTripDao).byName("trip_android.csv")!!.customName)
        assertEquals("rider-android", iphone.sync.findRestorableRider()?.storeId)
        assertTrue(iphone.sync.restoreSettingsFrom(BackupFolderSync.SETTINGS_BACKUP_NAME))

        iphone.record("trip_iphone.csv", csv("Ridden on iPhone"), status = 1)
        assertTrue(iphone.sync.uploadPass())
        val back = Ui()
        android.sync.runSync(back)
        assertEquals(SyncResult.Finished(1), back.result)
        assertEquals("Ridden on iPhone", (android.dao as MemoryTripDao).byName("trip_iphone.csv")!!.customName)
    }

    // --- the archive --------------------------------------------------------------

    @Test fun archivingMovesIntoTripsArchiveAndNeverOverwrites() = run {
        val folder = MemoryBackupFolder()
        folder.put("trips/trip.csv", "first ride")
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        val s = phone.settings.get()
        assertTrue(phone.sync.archiveInBackupFolder(s, "trip.csv"))
        assertEquals(1, folder.moves)
        assertEquals("first ride", folder.text("trips/archive/trip.csv"))

        // The same name again sits beside it, copied since a move cannot rename.
        folder.put("trips/trip.csv", "second ride")
        assertTrue(phone.sync.archiveInBackupFolder(s, "trip.csv"))
        assertEquals("second ride", folder.text("trips/archive/trip (1).csv"))
        assertEquals("first ride", folder.text("trips/archive/trip.csv"))
        assertTrue(folder.names("trips").none { it.endsWith(".csv") })

        // The folder's own spelling is found too; nothing to move is fine.
        folder.put("trips/Inmotion_V14.csv", "v14")
        folder.refuseMoves = true
        assertTrue(phone.sync.archiveInBackupFolder(s, "inmotion_v14.csv"))
        assertEquals("v14", folder.text("trips/archive/inmotion_v14.csv"))
        assertTrue(phone.sync.archiveInBackupFolder(s, "never_there.csv"))
    }

    @Test fun archiveNamesFollowDropboxsAutorename() {
        assertEquals("a.csv", BackupFolderSync.freeArchiveName(emptySet(), "a.csv"))
        assertEquals("a (2).csv", BackupFolderSync.freeArchiveName(setOf("a.csv", "a (1).csv"), "a.csv"))
        assertEquals("noext (1)", BackupFolderSync.freeArchiveName(setOf("noext"), "noext"))
    }

    // --- themes, presets and Dropbox ------------------------------------------------

    @Test fun themesAndPresetsAreFilesInThemesAndOverlays() = run {
        val folder = MemoryBackupFolder()
        val themes = FolderThemes { folder }
        val presets = FolderPresets { folder }
        assertTrue(themes.available())
        assertTrue(themes.save("My theme!", BuiltInThemes.dark.colors))
        assertEquals(listOf("My theme.json"), themes.listSaved())
        assertNotNull(themes.load("My theme.json"))
        assertNotNull(themes.load("My theme"))
        assertTrue(folder.text("themes/My theme.json")!!.contains("\"name\": \"My theme\""))

        assertTrue(presets.save("Night ride", OverlayPreset()))
        assertEquals(listOf("Night ride"), presets.list())
        assertEquals("Night ride", presets.load("Night ride")?.name)
        assertTrue(presets.delete("Night ride"))
        assertEquals(emptyList(), presets.list())

        val none = FolderThemes { null }
        assertFalse(none.available())
        assertFalse(none.save("x", BuiltInThemes.dark.colors))
        assertEquals(emptyList(), none.listSaved())
    }

    @Test fun themesAndOverlaysSyncWithDropboxBothWays() = run {
        val folder = MemoryBackupFolder()
        val phone = FolderTestPhone(folder).apply { pickFolder() }
        phone.settings.update {
            it.copy(dropboxAccessToken = "token", dropboxAccessTokenExpiresAt = 4_000_000_000_000L)
        }
        folder.put("themes/Mine.json", "{\"name\":\"Mine\"}")
        phone.server.put("/overlays/Race.json", "{\"name\":\"Race\"}".encodeToByteArray())

        assertEquals(0, phone.sync.countBackupSubdirConflicts(phone.settings.get()))
        val moved = phone.sync.syncBackupSubdirs(phone.settings.get(), SyncChoice.IGNORE)
        assertEquals(2, moved)
        assertEquals("{\"name\":\"Mine\"}", phone.server.get("/themes/Mine.json")?.decodeToString())
        assertEquals("{\"name\":\"Race\"}", folder.text("overlays/Race.json"))

        // The background mirror only adds what Dropbox lacks.
        folder.put("themes/Second.json", "{}")
        val mirror = phone.sync.mirrorBackupSubdirsMissingOnDropbox(phone.settings.get())
        assertEquals(FolderMirror(1, false), mirror)
        assertTrue(phone.server.exists("/themes/Second.json"))
    }
}
