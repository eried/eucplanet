package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.util.TripCsv
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Dropbox trip sync end to end, against [FakeDropbox]: the real
 * DropboxRepository builds every request, the shared passes decide, and the
 * fake answers as Dropbox does. Runs on the JVM (Android's code) and on the
 * iOS simulator (the iPhone's), so both phones move trips by the same rules.
 */
class DropboxTripSyncTest {

    /** A trip as Android's recorder writes it: CsvWriter's header, rows, and the Extra events. */
    private fun androidCsv(name: String? = null, speedTail: String = "") = buildString {
        append("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra\n")
        append("01.06.2026 20:24:31.204,0.00,100.80,31.00,92,12.0,59.911491,10.757933,1234.500,0.0,0.00,0.00,0.000,0.000,0.000,0.00,0.00,wheel.name=Master_1234\n")
        append("01.06.2026 20:24:32.204,12.50,100.70,31.00,92,12.0,59.911591,10.757933,1234.510,12.4,3.10,12.00,0.010,0.000,0.000,0.00,0.00,wheel.mac=AA:BB:CC:DD:EE:FF\n")
        append("01.06.2026 20:24:33.204,18.20,100.60,31.50,92,12.1,59.911691,10.757933,1234.520,18.1,5.20,20.00,0.020,0.000,0.000,0.00,0.00,wheel.brand=Begode\n")
        if (name != null) {
            append("01.06.2026 20:24:34.204,20.00,100.50,31.50,91,12.1,59.911791,10.757933,1234.530,19.9,6.00,22.00,0.020,0.000,0.000,0.00,0.00,trip.name=$name\n")
        }
        append("01.06.2026 20:24:35.204,21.00$speedTail,100.40,32.00,91,12.2,59.911891,10.757933,1234.540,20.9,6.20,23.00,0.020,0.000,0.000,0.00,0.00,\n")
    }

    private fun tripUploads(server: FakeDropbox) = server.uploads.filter { it.startsWith("/trips/") }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    // --- uploads ---------------------------------------------------------

    @Test fun newLocalTripsGoUpOnceAndAreMarkedBackedUp() = run {
        val server = FakeDropbox()
        val phone = SyncTestPhone(server).apply { link() }
        phone.record("trip_20260601_202431.csv", androidCsv())
        phone.record("trip_20260602_080000.csv", androidCsv(speedTail = "1"))
        // Not a trip: the export bundle sits in the same folder and must stay home.
        phone.files.writeWhole("trips_export.zip", "PK".encodeToByteArray())

        assertEquals(BackgroundOutcome.DONE, phone.background())

        assertEquals(
            setOf("/trips/trip_20260601_202431.csv", "/trips/trip_20260602_080000.csv"),
            tripUploads(server).toSet(),
        )
        assertFalse(server.exists("/trips/trips_export.zip"))
        assertEquals(androidCsv(), server.get("/trips/trip_20260601_202431.csv")!!.decodeToString())
        val dao = phone.dao as MemoryTripDao
        for (row in dao.all) {
            assertEquals(2, row.dropboxStatus, row.fileName)
            assertEquals(DropboxContentHash.of(server.get("/trips/${row.fileName}")!!), row.dropboxSyncedHash)
        }
        // The file wears Dropbox's timestamp, so an untouched trip matches next time.
        val f = phone.files.file("trip_20260601_202431.csv")
        assertEquals(server.modifiedSec("/trips/trip_20260601_202431.csv")!! * 1000L, f.lastModified())
        val s = phone.settings.get()
        assertFalse(s.dropboxSyncPending)
        assertEquals(0, s.dropboxPendingCount)
        assertTrue(s.dropboxLastSyncAt > 0)
        assertTrue(phone.host.retries.isEmpty())

        // A second pass has nothing to send.
        val before = tripUploads(server).size
        phone.background()
        assertEquals(before, tripUploads(server).size)
    }

    @Test fun syncAllUploadsAndReportsTheCount() = run {
        val server = FakeDropbox()
        val phone = SyncTestPhone(server).apply { link() }
        phone.record("a.csv", androidCsv())
        phone.record("b.csv", androidCsv(speedTail = "1"))
        val ui = phone.syncAll()
        assertTrue(ui.asked.isEmpty(), "nothing to ask about")
        assertEquals(SyncResult.Finished(2), ui.result)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), ui.progress)
        assertEquals(2, tripUploads(server).size)

        val again = phone.syncAll()
        assertEquals(SyncResult.UpToDate, again.result)
        assertEquals(2, tripUploads(server).size)
    }

    @Test fun aFailedUploadIsMarkedAndRetried() = run {
        val server = FakeDropbox()
        val phone = SyncTestPhone(server).apply { link() }
        phone.record("good.csv", androidCsv())
        phone.record("bad.csv", androidCsv(speedTail = "1"))
        server.failingUploads += "/trips/bad.csv"

        phone.background(attempt = 2)

        val dao = phone.dao as MemoryTripDao
        assertEquals(2, dao.byName("good.csv")!!.dropboxStatus)
        assertEquals(3, dao.byName("bad.csv")!!.dropboxStatus)
        val s = phone.settings.get()
        assertTrue(s.dropboxSyncPending)
        assertEquals(1, s.dropboxPendingCount)
        assertEquals(listOf<Pair<Int, Long?>>(3 to null), phone.host.retries, "the next attempt goes up the curve")
    }

    @Test fun aRefusalBacksOffHalfAnHour() = run {
        val server = FakeDropbox()
        val phone = SyncTestPhone(server).apply { link() }
        phone.record("a.csv", androidCsv())
        server.refuseAll = true
        assertEquals(BackgroundOutcome.DONE, phone.background())
        assertEquals(listOf<Pair<Int, Long?>>(0 to DropboxTripSync.REFUSED_RETRY_SECONDS), phone.host.retries)

        val ui = phone.syncAll()
        assertEquals(SyncResult.NoFolder, ui.result, "the list fails, as a dropped connection does")
    }

    // --- downloads -------------------------------------------------------

    @Test fun aTripRecordedOnAndroidArrivesOnTheOtherPhoneAsTheSameRow() = run {
        val server = FakeDropbox()
        val android = SyncTestPhone(server).apply { link() }
        val iphone = SyncTestPhone(server).apply { link() }
        val csv = androidCsv(name = "Coast road")
        android.record("trip_20260601_202431.csv", csv)
        android.background()

        val ui = iphone.syncAll()

        assertEquals(SyncResult.Finished(1), ui.result)
        assertEquals(csv, iphone.fileText("trip_20260601_202431.csv"), "byte for byte")
        val row = (iphone.dao as MemoryTripDao).byName("trip_20260601_202431.csv")
        assertNotNull(row)
        assertEquals(TripCsv.parseDate("01.06.2026 20:24:31.204"), row.startTime)
        assertEquals(TripCsv.parseDate("01.06.2026 20:24:35.204"), row.endTime)
        // Five fixes 11 m apart: 44 m by GPS, preferred over the odometer's 40 m.
        assertTrue(abs(row.distanceKm - 0.0445f) < 0.001f, "distance ${row.distanceKm}")
        assertEquals("Coast road", row.customName)
        val wheel = JSONObject(row.wheelMetaJson!!)
        assertEquals("Master_1234", wheel.getString("ble_name"))
        assertEquals("AA:BB:CC:DD:EE:FF", wheel.getString("ble_mac"))
        assertEquals("Begode", wheel.getString("brand"))
        assertEquals(2, row.dropboxStatus)
        assertEquals(0, row.uploadStatus, "no backup folder to mirror into")
        assertEquals(DropboxContentHash.of(csv.encodeToByteArray()), row.dropboxSyncedHash)
        // Dropbox's timestamp travels with the file, so it is not sent back up.
        val before = tripUploads(server).size
        iphone.background()
        assertEquals(before, tripUploads(server).size)
    }

    @Test fun theBackgroundPassOnlyPullsWhenTheRiderAsked() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        a.record("one.csv", androidCsv())
        a.record("two.csv", androidCsv(speedTail = "1"))
        a.background()

        val b = SyncTestPhone(server).apply { link() }
        b.background()
        assertTrue(server.downloads.none { it.startsWith("/trips/") }, "a backup-only link downloads nothing")
        assertTrue((b.dao as MemoryTripDao).all.isEmpty())

        b.settings.update { it.copy(dropboxPullRequested = true) }
        b.background()
        assertEquals(setOf("one.csv", "two.csv"), (b.dao as MemoryTripDao).all.map { it.fileName }.toSet())
        val s = b.settings.get()
        assertFalse(s.dropboxPullRequested, "the request is finished")
        assertFalse(s.dropboxSyncPending)
    }

    @Test fun aPullOutOfTimeComesBackForTheRest() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        a.record("one.csv", androidCsv())
        a.record("two.csv", androidCsv(speedTail = "1"))
        a.background()

        val b = SyncTestPhone(server).apply { link() }
        b.settings.update { it.copy(dropboxPullRequested = true) }
        // No time at all: the pass stops before the first file.
        b.sync.backgroundPass(0, isStopped = { false }, foregroundRunning = { false }, downloadBudgetMs = -1)
        assertTrue((b.dao as MemoryTripDao).all.isEmpty())
        val s = b.settings.get()
        assertTrue(s.dropboxPullRequested)
        assertTrue(s.dropboxSyncPending)
        assertEquals(2, s.dropboxPendingCount)
        assertEquals(listOf<Pair<Int, Long?>>(1 to null), b.host.retries)
    }

    @Test fun aLongListingIsReadToTheEnd() = run {
        val server = FakeDropbox(pageSize = 2)
        val a = SyncTestPhone(server).apply { link() }
        repeat(5) { a.record("t$it.csv", androidCsv(speedTail = "$it")) }
        a.background()
        assertEquals(5, tripUploads(server).size)
        // Every page was seen, or the last trips would go up again forever.
        a.background()
        assertEquals(5, tripUploads(server).size)
        assertTrue(server.calls.any { it == "list /trips" })
    }

    // --- a trip both sides have ------------------------------------------

    @Test fun theSameTripOnBothSidesIsNeitherWorkNorAConflict() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        a.record("same.csv", androidCsv())
        a.background()
        val b = SyncTestPhone(server).apply { link() }
        b.record("same.csv", androidCsv())

        val ui = b.syncAll()
        assertTrue(ui.asked.isEmpty())
        assertEquals(SyncResult.UpToDate, ui.result)
        assertEquals(1, tripUploads(server).size)
        // Remembered as synced, so a later one-sided edit is not asked about.
        assertEquals(
            DropboxContentHash.of(androidCsv().encodeToByteArray()),
            (b.dao as MemoryTripDao).byName("same.csv")!!.dropboxSyncedHash,
        )
    }

    @Test fun anEditOnOnePhoneReachesTheOtherWithoutAsking() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val b = SyncTestPhone(server).apply { link() }
        a.record("ride.csv", androidCsv())
        a.background()
        b.syncAll()

        // A renames it: the name goes into the file.
        a.edit("ride.csv", androidCsv(name = "Beach ride"))
        a.background()
        assertEquals(androidCsv(name = "Beach ride"), server.get("/trips/ride.csv")!!.decodeToString())

        val ui = b.syncAll()
        assertTrue(ui.asked.isEmpty(), "Dropbox moved on and B did not: no question")
        assertEquals(androidCsv(name = "Beach ride"), b.fileText("ride.csv"))
        assertEquals("Beach ride", (b.dao as MemoryTripDao).byName("ride.csv")!!.customName)
    }

    @Test fun theBackgroundNeverOverwritesAnotherPhonesEdit() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val b = SyncTestPhone(server).apply { link() }
        a.record("ride.csv", androidCsv())
        a.background()
        b.syncAll()

        a.edit("ride.csv", androidCsv(name = "From A"))
        a.background()
        b.edit("ride.csv", androidCsv(name = "From phone B"))
        b.background()
        assertEquals(androidCsv(name = "From A"), server.get("/trips/ride.csv")!!.decodeToString())
    }

    @Test fun editsOnBothSidesAskOnceAndFollowTheAnswer() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val b = SyncTestPhone(server).apply { link() }
        a.record("ride.csv", androidCsv())
        a.background()
        b.syncAll()
        a.edit("ride.csv", androidCsv(name = "From A"))
        a.background()
        b.edit("ride.csv", androidCsv(name = "From phone B"))

        // Cancel: nothing moves and the indicator goes.
        val cancel = b.syncAll(ScriptedUi(SyncChoice.CANCEL))
        assertEquals(listOf(1), cancel.asked)
        assertEquals(androidCsv(name = "From phone B"), b.fileText("ride.csv"))
        assertEquals(1, b.host.cancels, "the queued watchdog is dropped too")
        assertFalse(b.settings.get().dropboxSyncPending)

        // Skip: still nothing.
        b.syncAll(ScriptedUi(SyncChoice.IGNORE))
        assertEquals(androidCsv(name = "From A"), server.get("/trips/ride.csv")!!.decodeToString())

        // Keep Dropbox's copy: the file and the row follow it.
        b.syncAll(ScriptedUi(SyncChoice.FOLDER))
        assertEquals(androidCsv(name = "From A"), b.fileText("ride.csv"))
        assertEquals("From A", (b.dao as MemoryTripDao).byName("ride.csv")!!.customName)

        // And the other way: keep this phone's.
        a.edit("ride.csv", androidCsv(name = "A again"))
        a.background()
        b.edit("ride.csv", androidCsv(name = "B wins"))
        b.syncAll(ScriptedUi(SyncChoice.APP))
        assertEquals(androidCsv(name = "B wins"), server.get("/trips/ride.csv")!!.decodeToString())
    }

    // --- deleting and archiving ------------------------------------------

    @Test fun archivingMovesTheTripOutOfTheSyncOnDropbox() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        a.record("old.csv", androidCsv())
        a.record("one.csv", androidCsv(speedTail = "1"))
        a.record("two.csv", androidCsv(speedTail = "2"))
        a.background()

        assertTrue(a.sync.archiveTripFile("old.csv"))
        assertFalse(server.exists("/trips/old.csv"))
        assertTrue(server.exists("/trips/archive/old.csv"))

        assertEquals(setOf("one.csv", "two.csv"), a.sync.archiveTripFiles(listOf("one.csv", "two.csv")))
        assertEquals(setOf("old.csv", "one.csv", "two.csv"), server.names("/trips/archive").toSet())

        // A trip that never reached Dropbox is archived by definition.
        assertTrue(a.sync.archiveTripFile("never.csv"))

        // The same name archived again sits beside the first, never on top.
        a.record("old.csv", androidCsv(speedTail = "9"))
        a.background()
        assertTrue(a.sync.archiveTripFile("old.csv"))
        assertTrue(server.exists("/trips/archive/old (1).csv"))
        assertEquals(androidCsv(), server.get("/trips/archive/old.csv")!!.decodeToString())
    }

    @Test fun aTripArchivedOnAnotherPhoneIsArchivedHereToo() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val b = SyncTestPhone(server).apply { link() }
        a.record("split.csv", androidCsv())
        a.record("kept.csv", androidCsv(speedTail = "1"))
        a.background()
        b.syncAll()
        // B also has its own different copy of a name A archives.
        assertTrue(a.sync.archiveTripFile("split.csv"))

        b.background()

        assertNull((b.dao as MemoryTripDao).byName("split.csv"), "the row goes")
        assertNull(b.fileText("split.csv"), "the file goes")
        assertNotNull(b.fileText("kept.csv"))
        assertFalse(server.exists("/trips/split.csv"), "and it is not uploaded back")
    }

    @Test fun aDifferentCopyOfAnArchivedNameIsKept() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val b = SyncTestPhone(server).apply { link() }
        a.record("ride.csv", androidCsv())
        a.background()
        assertTrue(a.sync.archiveTripFile("ride.csv"))
        b.record("ride.csv", androidCsv(name = "Only on B"))

        b.background()

        assertNotNull(b.fileText("ride.csv"), "it exists once, so it is not dropped")
        assertEquals(androidCsv(name = "Only on B"), server.get("/trips/ride.csv")!!.decodeToString())
    }

    @Test fun aPlainDeleteLeavesDropboxAloneSoSyncAllBringsItBack() = run {
        // Android's delete without "archive" removes the phone's copy only.
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        val row = a.record("ride.csv", androidCsv())
        a.background()
        a.dao.delete(row)
        a.files.file("ride.csv").delete()

        a.background()
        assertTrue(server.exists("/trips/ride.csv"))
        assertNull((a.dao as MemoryTripDao).byName("ride.csv"), "the background pass pulls only on request")

        a.syncAll()
        assertNotNull((a.dao as MemoryTripDao).byName("ride.csv"))
    }

    // --- the CSV reader ---------------------------------------------------

    @Test fun theRowReaderTakesWhatTheFileSays() {
        val files = MemoryTripFiles { 0L }
        files.write("t.csv", androidCsv(name = "Coast road"))
        val meta = TripCsvMeta.read(files.file("t.csv"))
        assertEquals("Coast road", meta.name)
        assertEquals(TripCsv.parseDate("01.06.2026 20:24:31.204"), meta.startTime)
        // Key order is the JSON library's (the JVM test runs maven org.json, not AOSP's).
        val wheel = JSONObject(meta.wheelJson!!)
        assertEquals(3, wheel.length())
        assertEquals("Master_1234", wheel.getString("ble_name"))
        assertEquals("AA:BB:CC:DD:EE:FF", wheel.getString("ble_mac"))
        assertEquals("Begode", wheel.getString("brand"))

        files.write("empty.csv", "")
        val empty = TripCsvMeta.read(files.file("empty.csv"))
        assertEquals(0f, empty.distanceKm)
        assertNull(empty.name)
        assertEquals(empty.startTime, empty.endTime)

        // No GPS: the odometer's span.
        files.write("odo.csv", "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,Extra\n" +
            "01.06.2026 20:00:00.000,1,1,1,1,0,0,0,100.0,\n01.06.2026 20:10:00.000,1,1,1,1,0,0,0,102.5,\n")
        assertEquals(2.5f, TripCsvMeta.read(files.file("odo.csv")).distanceKm)

        // A file that cannot be read gives the defaults rather than failing the sync.
        val gone = TripCsvMeta.read(files.file("missing.csv"))
        assertNull(gone.wheelJson)
    }

    @Test fun theRetryCurveIsAndroids() {
        assertEquals(listOf(0L, 15L, 30L, 60L, 120L, 240L, 300L, 300L),
            (0..7).map { DropboxTripSync.delayForAttempt(it) })
    }

    @Test fun rowsInsertedByADownloadKeepTheirTripRecordShape() = run {
        val server = FakeDropbox()
        val a = SyncTestPhone(server).apply { link() }
        a.record("t.csv", androidCsv())
        a.background()
        val b = SyncTestPhone(server).apply { link() }
        b.syncAll()
        val row: TripRecord = b.dao.observeAll().first().single()
        assertEquals("t.csv", row.fileName)
        assertNull(row.tripUuid, "a download is not this phone's live trip, so it is not a leaderboard upload")
        assertEquals(0, row.eucstatsStatus)
    }
}
