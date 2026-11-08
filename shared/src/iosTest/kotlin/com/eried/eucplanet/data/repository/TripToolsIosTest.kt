package com.eried.eucplanet.data.repository

import com.eried.eucplanet.app.IosTripToolFiles
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.sync.MemoryTripDao
import com.eried.eucplanet.util.TripCsv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trip tools on the iPhone's own trips folder ([IosTripToolFiles]): every
 * file they leave is byte for byte what the same tools leave in the folder
 * TripToolsTest checks on the JVM, so an iPhone split is Android's split.
 */
@OptIn(ExperimentalForeignApi::class)
class TripToolsIosTest {
    private val dir = NSTemporaryDirectory() + "trip-tools-test"

    @AfterTest fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    private class Phone(val files: TripToolFiles, val put: (String, String) -> Unit, val read: (String) -> String?, val names: () -> Set<String>) {
        val dao = MemoryTripDao()
        val tools = TripTools(dao, files, profileNames = { emptyList() }, resync = {}, now = { TripToolsTest.NOW })

        suspend fun recorded(name: String, csv: String): TripRecord {
            put(name, csv)
            val lines = csv.readerLines().toList()
            val row = TripRecord(
                fileName = name,
                startTime = TripCsv.parseDate(lines[1].substringBefore(','))!!,
                endTime = TripCsv.parseDate(lines.last().substringBefore(','))!!,
                wheelMetaJson = TripToolsTest.WHEEL_JSON,
            )
            return row.copy(id = dao.insert(row))
        }

        /** The same edits, in the same order, on either folder. */
        suspend fun edits() {
            val a = recorded("trip_a.csv", TripToolsTest.ride(10, name = "Coast road"))
            val b = recorded("trip_b.csv", TripToolsTest.ride(4, startSec = 40))
            tools.splitTrip(a, listOf(TripCsv.parseDate("01.06.2026 20:20:06.204")!!))
            tools.combineTrips(listOf(a, b))
            tools.renameTrip(b, "Hill, climb")
            tools.changeTripWheel(a, WheelChoice(name = "Sherman", brand = "Veteran"))
            tools.saveTripSection(b, 0L, Long.MAX_VALUE)
        }
    }

    @Test fun theIphonesFolderGetsTheSameFilesAsTheSharedOne() = runBlocking {
        cleanUp()
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        val ios = Phone(
            IosTripToolFiles { dir },
            put = { n, t -> (t as NSString).writeToFile("$dir/$n", true, NSUTF8StringEncoding, null) },
            read = { n -> NSString.stringWithContentsOfFile("$dir/$n", NSUTF8StringEncoding, null) },
            names = {
                NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null).orEmpty()
                    .filterIsInstance<String>().toSet()
            },
        )
        val mem = MemoryToolFiles()
        val shared = Phone(mem, put = { n, t -> mem.store.write(n, t) }, read = { mem.store.text(it) }, names = { mem.store.stored.keys.toSet() })

        ios.edits()
        shared.edits()

        assertEquals(shared.names(), ios.names())
        for (name in shared.names()) assertEquals(shared.read(name), ios.read(name), name)
        assertEquals(
            shared.dao.all.map { it.copy(id = 0) }.sortedBy { it.fileName },
            ios.dao.all.map { it.copy(id = 0) }.sortedBy { it.fileName },
        )
    }
}
