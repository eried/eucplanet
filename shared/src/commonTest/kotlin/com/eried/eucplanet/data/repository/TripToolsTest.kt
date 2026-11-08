package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.sync.FakeDropbox
import com.eried.eucplanet.data.sync.MemoryTripDao
import com.eried.eucplanet.data.sync.MemoryTripFiles
import com.eried.eucplanet.data.sync.SyncResult
import com.eried.eucplanet.data.sync.SyncTestPhone
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.formatLocalDateUs
import com.eried.eucplanet.util.TripCsv
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A trips folder in memory for the trip tools, over the sync tests' [MemoryTripFiles]. */
class MemoryToolFiles(val store: MemoryTripFiles = MemoryTripFiles { 0L }) : TripToolFiles {
    override fun exists(name: String) = name in store.stored
    override fun length(name: String) = store.stored[name]?.bytes?.size?.toLong() ?: 0L
    override fun delete(name: String) = store.stored.remove(name) != null
    override fun rename(from: String, to: String): Boolean {
        val s = store.stored.remove(from) ?: return false
        store.stored[to] = s
        return true
    }
    override fun lines(name: String): LineSource = object : LineSource {
        override fun <R> useLines(block: (Sequence<String>) -> R): R =
            block((store.text(name) ?: error("$name is gone")).readerLines())
    }
    override fun <R> write(name: String, block: (LineSink) -> R): R {
        val out = StringBuilder()
        var any = false
        val r = block(LineSink { any = true; out.append(it).append('\n') })
        if (any) store.write(name, out.toString())
        return r
    }
}

/**
 * The trip tools as both phones run them ([TripTools] over a folder in
 * memory): the files they write, the rows they add and what Dropbox gets.
 * The iPhone's own folder is checked against these in TripToolsIosTest.
 */
class TripToolsTest {
    companion object {
        const val HEADER = "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra"

        /** A ride as Android's recorder writes it: one row a second, the wheel near the top. */
        fun ride(rows: Int, startSec: Int = 0, name: String? = null): String = buildString {
            append(HEADER).append('\n')
            for (i in 0 until rows) {
                val s = startSec + i
                val extra = when (i) {
                    0 -> "wheel.name=Master_1234"
                    1 -> "wheel.mac=AA:BB:CC:DD:EE:FF"
                    2 -> "wheel.brand=Begode"
                    3 -> name?.let { "trip.name=$it" } ?: ""
                    else -> ""
                }
                append("01.06.2026 20:%02d:%02d.204,12.50,100.70,31.00,92,12.0,%.6f,10.757933,%.3f,12.4,3.10,12.00,0.010,0.000,0.000,0.00,0.00,%s\n"
                    .format0(20 + s / 60, s % 60, 59.911491 + i * 0.0001, 1234.5 + i * 0.01, extra))
            }
        }

        /** String.format is JVM only: the few patterns [ride] needs, by hand. */
        private fun String.format0(min: Int, sec: Int, lat: Double, mil: Double, extra: String): String {
            fun two(n: Int) = n.toString().padStart(2, '0')
            fun fixed(v: Double, d: Int): String {
                var scale = 1L
                repeat(d) { scale *= 10 }
                val n = kotlin.math.round(v * scale).toLong()
                val whole = n / scale
                val frac = (n % scale).toString().padStart(d, '0')
                return "$whole.$frac"
            }
            return replace("%02d:%02d", "${two(min)}:${two(sec)}")
                .replace("%.6f", fixed(lat, 6))
                .replace("%.3f", fixed(mil, 3))
                .replace("%s", extra)
        }

        const val NOW = 1_780_000_000_000L
        val STAMP = formatLocalDateUs("yyyyMMdd_HHmmss", NOW)
        const val WHEEL_JSON = """{"ble_name":"Master_1234","ble_mac":"AABBCCDDEEFF","brand":"Begode"}"""
    }

    private val dao = MemoryTripDao()
    private val files = MemoryToolFiles()
    private val resynced = mutableListOf<Long>()
    private val tools = TripTools(dao, files, profileNames = { listOf("Sherman") }, resync = { resynced += it }, now = { NOW })

    private suspend fun recorded(name: String, csv: String, wheelJson: String? = WHEEL_JSON): TripRecord {
        files.store.write(name, csv)
        val lines = csv.readerLines().toList()
        val row = TripRecord(
            fileName = name,
            startTime = TripCsv.parseDate(lines[1].substringBefore(','))!!,
            endTime = TripCsv.parseDate(lines.last().substringBefore(','))!!,
            distanceKm = 1f,
            tripUuid = "uuid-$name",
            wheelMetaJson = wheelJson,
        )
        return row.copy(id = dao.insert(row))
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private fun text(name: String) = files.store.text(name)
    private fun dataLines(name: String) = text(name)!!.readerLines().drop(1).toList()

    @Test fun readerLinesMatchReadLine() {
        assertEquals(listOf("a", "b"), "a\nb\n".readerLines().toList())
        assertEquals(listOf("a", "b"), "a\r\nb".readerLines().toList())
        assertEquals(listOf("a", ""), "a\n\n".readerLines().toList())
        assertEquals(emptyList(), "".readerLines().toList())
    }

    @Test fun aSplitWritesOneDerivedPiecePerSectionEachCarryingTheWheel() = run {
        val src = recorded("trip_20260601_202000.csv", ride(10, name = "Coast road"))
        val cut = TripCsv.parseDate("01.06.2026 20:20:06.204")!!

        val pieces = tools.splitTrip(src, listOf(cut))

        assertEquals(2, pieces.size)
        assertEquals("trip_20260601_202000_derived${STAMP}_1.csv", pieces[0].fileName)
        assertEquals("trip_20260601_202000_derived${STAMP}_2.csv", pieces[1].fileName)
        assertEquals(6, pieces[0].sampleCount)
        assertEquals(4, pieces[1].sampleCount)
        for (p in pieces) {
            assertNull(p.tripUuid, "derived trips stay off the leaderboard")
            assertEquals(WHEEL_JSON, p.wheelMetaJson)
            assertEquals(HEADER, text(p.fileName)!!.readerLines().first())
            val extras = dataLines(p.fileName).map { it.substringAfterLast(',') }
            assertFalse(extras.any { it.startsWith("trip.name=") }, "a piece starts nameless: ${p.fileName}")
            assertTrue("wheel.name=Master_1234" in extras, "${p.fileName}: $extras")
            assertTrue("wheel.mac=AABBCCDDEEFF" in extras, "${p.fileName}: $extras")
            assertTrue("wheel.brand=Begode" in extras, "${p.fileName}: $extras")
        }
        // The second piece's rows are the source's, verbatim up to the Extra cell.
        assertEquals(
            ride(10, name = "Coast road").readerLines().drop(7).map { it.substringBeforeLast(',') }.toList(),
            dataLines(pieces[1].fileName).map { it.substringBeforeLast(',') },
        )
        assertEquals(TripCsv.parseDate("01.06.2026 20:20:06.204"), pieces[1].startTime)
        assertEquals(TripCsv.parseDate("01.06.2026 20:20:09.204"), pieces[1].endTime)
        assertEquals(pieces.map { it.id }, resynced, "each piece goes to the backups at once")
        assertEquals(ride(10, name = "Coast road"), text(src.fileName), "the source is untouched")
        assertEquals(3, dao.all.size)
    }

    @Test fun aSectionIsOneDerivedTrip() = run {
        val src = recorded("trip_a.csv", ride(10))
        val piece = tools.saveTripSection(
            src,
            TripCsv.parseDate("01.06.2026 20:20:02.204")!!,
            TripCsv.parseDate("01.06.2026 20:20:04.204")!!,
        )
        assertNotNull(piece)
        assertEquals("trip_a_derived$STAMP.csv", piece.fileName)
        assertEquals(3, piece.sampleCount)
        assertTrue(resynced.isEmpty(), "a section is not pushed by itself, as on Android")
        assertNull(tools.saveTripSection(src, 0L, 1L), "an empty range makes nothing")
        assertFalse(files.store.stored.keys.any { it.endsWith(".rewrite") })
    }

    @Test fun combiningJoinsOldestFirstUnderOneHeader() = run {
        val late = recorded("trip_late.csv", ride(3, startSec = 30, name = "Late"))
        val early = recorded("trip_early.csv", ride(4, startSec = 0))

        val joined = tools.combineTrips(listOf(late, early))

        assertNotNull(joined)
        assertEquals("trip_early_derived${STAMP}_join.csv", joined.fileName)
        assertEquals(7, joined.sampleCount)
        val lines = text(joined.fileName)!!.readerLines().toList()
        assertEquals(1, lines.count { it == HEADER })
        assertTrue(lines[1].startsWith("01.06.2026 20:20:00"))
        assertTrue(lines.last().startsWith("01.06.2026 20:20:32"))
        assertFalse(lines.any { "trip.name=" in it }, "a join starts nameless")
        assertEquals(early.startTime, joined.startTime)
        assertEquals(late.endTime, joined.endTime)
        assertNull(joined.tripUuid)
        assertEquals(listOf(joined.id), resynced)
        assertNull(tools.combineTrips(listOf(early)), "one trip is not a join")
    }

    @Test fun aRenameRewritesTheFileAndTheRow() = run {
        val src = recorded("trip_r.csv", ride(5))
        tools.renameTrip(src, "  Home, then \"pier\"  ")
        val extras = dataLines("trip_r.csv").map { it.substringAfterLast(',') }
        assertEquals(listOf("wheel.name=Master_1234", "wheel.mac=AA:BB:CC:DD:EE:FF", "wheel.brand=Begode", "trip.name=Home then pier", ""), extras)
        assertEquals("Home, then \"pier\"", dao.getById(src.id)!!.customName)
        assertEquals(setOf("trip_r.csv"), files.store.stored.keys, "no .rewrite or .bak left")
        assertEquals(listOf(src.id), resynced)

        tools.renameTrip(dao.getById(src.id)!!, "")
        assertFalse(text("trip_r.csv")!!.contains("trip.name="))
        assertNull(dao.getById(src.id)!!.customName)
    }

    @Test fun changingTheWheelReplacesTheWholeIdentity() = run {
        val src = recorded("trip_w.csv", ride(5))
        tools.changeTripWheel(src, WheelChoice(name = "Sherman"))
        val extras = dataLines("trip_w.csv").map { it.substringAfterLast(',') }
        assertEquals(listOf("wheel.name=Sherman", "", "", "", ""), extras)
        assertEquals("""{"ble_name":"Sherman"}""", dao.getById(src.id)!!.wheelMetaJson)
        assertEquals(listOf(src.id), resynced)
    }

    @Test fun knownWheelsFoldProfilesIntoTripIdentities() = run {
        recorded("trip_k.csv", ride(3))
        recorded("trip_k2.csv", ride(3), wheelJson = """{"ble_name":"Sherman"}""")
        val wheels = tools.knownWheels()
        assertEquals(listOf("Begode", "Sherman"), wheels.map { it.label })
    }

    @Test fun theBackfillReadsTheWheelOutOfAFileOnce() = run {
        val src = recorded("trip_b.csv", ride(4), wheelJson = null)
        val bare = recorded("trip_c.csv", ride(1).replace("wheel.name=Master_1234", ""), wheelJson = null)
        tools.backfillWheelMetaFromFiles()
        val json = JSONObject(dao.getById(src.id)!!.wheelMetaJson!!)
        assertEquals("Master_1234", json.getString("ble_name"))
        assertEquals("AA:BB:CC:DD:EE:FF", json.getString("ble_mac"))
        assertEquals("Begode", json.getString("brand"))
        assertEquals("{}", dao.getById(bare.id)!!.wheelMetaJson, "looked, nothing there")
    }

    /** A rename on one phone reaches the other through Dropbox, file and name. */
    @Test fun anEditedTripGoesUpAtOnceAndTheOtherPhoneGetsIt() = run {
        val server = FakeDropbox()
        val iphone = SyncTestPhone(server).apply { link() }
        val android = SyncTestPhone(server).apply { link() }
        val store = iphone.files as MemoryTripFiles
        val trip = iphone.record("trip_20260601_202000.csv", ride(5))
        iphone.background()
        val phoneTools = TripTools(
            iphone.dao, MemoryToolFiles(store), profileNames = { emptyList() },
            resync = { id -> iphone.sync.pushEditedTrip(iphone.dao.getById(id)!!.fileName) },
            now = { NOW },
        )

        phoneTools.renameTrip(trip, "Coast road")
        val pieces = phoneTools.splitTrip(iphone.dao.getById(trip.id)!!, listOf(TripCsv.parseDate("01.06.2026 20:20:03.204")!!))

        val iphoneDao = iphone.dao as MemoryTripDao
        assertEquals(2, iphoneDao.getById(trip.id)!!.dropboxStatus, "backed up")
        assertEquals(store.text(trip.fileName), server.get("/trips/${trip.fileName}")!!.decodeToString())
        for (p in pieces) {
            assertEquals(2, iphoneDao.getById(p.id)!!.dropboxStatus)
            assertEquals(store.text(p.fileName), server.get("/trips/${p.fileName}")!!.decodeToString())
        }
        assertTrue(iphone.host.retries.isEmpty())

        val ui = android.syncAll()
        assertEquals(SyncResult.Finished(3), ui.result)
        val androidDao = android.dao as MemoryTripDao
        assertEquals("Coast road", androidDao.byName(trip.fileName)!!.customName)
        for (p in pieces) assertNotNull(androidDao.byName(p.fileName))
    }

    @Test fun aFailedPushIsMarkedAndRetried() = run {
        val server = FakeDropbox()
        val phone = SyncTestPhone(server).apply { link() }
        val trip = phone.record("trip_f.csv", ride(3))
        server.failingUploads += "/trips/trip_f.csv"
        phone.sync.pushEditedTrip(trip.fileName)
        assertEquals(3, (phone.dao as MemoryTripDao).getById(trip.id)!!.dropboxStatus)
        assertEquals(listOf<Pair<Int, Long?>>(1 to null), phone.host.retries)
    }
}
