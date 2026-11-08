package com.eried.eucplanet.data.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eried.eucplanet.app.IosTripFiles
import com.eried.eucplanet.data.db.AppDatabase
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.util.TripCsv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cross-platform round trip on the iPhone's own storage: the shared
 * Room database on the bundled SQLite and the real Documents-style trips
 * folder ([IosTripFiles]), syncing through [FakeDropbox] with a phone that
 * runs Android's side in memory.
 */
@OptIn(ExperimentalForeignApi::class)
class DropboxTripSyncIosTest {
    private val root = NSTemporaryDirectory() + "dropbox-trip-sync-test"
    private val dbPath = "$root/eucplanet.db"
    private val tripsDir = "$root/trips"
    private var db: AppDatabase? = null

    @AfterTest fun cleanUp() {
        db?.close()
        db = null
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    private fun iphone(server: FakeDropbox): SyncTestPhone {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        val database = Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        db = database
        return SyncTestPhone(server, dao = database.tripDao()) { IosTripFiles { tripsDir } }
    }

    private val androidCsv =
        "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra\n" +
            "01.06.2026 20:24:31.204,0.00,100.80,31.00,92,12.0,59.911491,10.757933,1234.500,0.0,0.00,0.00,0.000,0.000,0.000,0.00,0.00,wheel.name=Master_1234\n" +
            "01.06.2026 20:24:32.204,12.50,100.70,31.00,92,12.0,59.911591,10.757933,1234.510,12.4,3.10,12.00,0.010,0.000,0.000,0.00,0.00,trip.name=Coast road\n" +
            "01.06.2026 20:24:33.204,18.20,100.60,31.50,92,12.1,59.911691,10.757933,1234.520,18.1,5.20,20.00,0.020,0.000,0.000,0.00,0.00,\n"

    @Test fun anAndroidTripLandsInTheIphonesDatabaseAndFolder() = runBlocking {
        cleanUp()
        val server = FakeDropbox()
        val android = SyncTestPhone(server).apply { link() }
        android.record("trip_20260601_202431.csv", androidCsv)
        android.background()

        val phone = iphone(server).apply { link() }
        val ui = phone.syncAll()

        assertEquals(SyncResult.Finished(1), ui.result)
        val file = phone.files.file("trip_20260601_202431.csv")
        assertEquals(androidCsv, file.readBytes().decodeToString())
        // The file system took Dropbox's date, which the next pass compares against.
        assertEquals(server.modifiedSec("/trips/trip_20260601_202431.csv")!! * 1000L, file.lastModified())
        val row = phone.dao.findByFileName("trip_20260601_202431.csv")
        assertNotNull(row)
        assertEquals(TripCsv.parseDate("01.06.2026 20:24:31.204"), row.startTime)
        assertEquals(TripCsv.parseDate("01.06.2026 20:24:33.204"), row.endTime)
        assertEquals("Coast road", row.customName)
        assertEquals("""{"ble_name":"Master_1234"}""", row.wheelMetaJson)
        assertEquals(2, row.dropboxStatus)
        assertEquals(DropboxContentHash.of(androidCsv.encodeToByteArray()), row.dropboxSyncedHash)

        // Nothing goes back up.
        val uploads = server.uploads.count { it.startsWith("/trips/") }
        phone.background()
        assertEquals(uploads, server.uploads.count { it.startsWith("/trips/") })
    }

    @Test fun anIphoneTripReachesAndroid() = runBlocking {
        cleanUp()
        val server = FakeDropbox()
        val phone = iphone(server).apply { link() }
        val csv = androidCsv.replace("Coast road", "Fjord loop")
        phone.files.writeWhole("trip_20260602_090000.csv", csv.encodeToByteArray())
        phone.dao.insert(TripRecord(fileName = "trip_20260602_090000.csv", startTime = 1L, endTime = 2L))
        assertTrue(phone.files.files().any { it.name == "trip_20260602_090000.csv" })

        phone.background()
        assertEquals(csv, server.get("/trips/trip_20260602_090000.csv")?.decodeToString())
        assertEquals(2, phone.dao.observeAll().first().single().dropboxStatus)

        val android = SyncTestPhone(server).apply { link() }
        android.syncAll()
        assertEquals("Fjord loop", android.dao.findByFileName("trip_20260602_090000.csv")?.customName)

        // Archived on Android, it leaves the iPhone too.
        assertTrue(android.sync.archiveTripFile("trip_20260602_090000.csv"))
        phone.background()
        assertEquals(null, phone.dao.findByFileName("trip_20260602_090000.csv"))
        assertTrue(phone.files.files().none { it.name == "trip_20260602_090000.csv" })
    }
}
