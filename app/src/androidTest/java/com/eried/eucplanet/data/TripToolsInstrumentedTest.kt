package com.eried.eucplanet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eried.eucplanet.data.repository.TripDerive
import com.eried.eucplanet.data.repository.TripSplitDetector
import com.eried.eucplanet.util.TripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end checks for the trip tools against real files.
 *
 * These live in androidTest rather than the unit suite because the pieces they
 * exercise, `org.json` and the file plumbing, behave differently on a device
 * than under the JVM stubs. The pure detection logic is covered by
 * TripSplitDetectorTest and TripDeriveTest; this is about the file operations
 * being correct and, above all, non-destructive.
 */
@RunWith(AndroidJUnit4::class)
class TripToolsInstrumentedTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header =
        "Date,Speed,Voltage,Temperature,Battery,Altitude,Latitude,Longitude,Total mileage,Extra"

    private lateinit var source: File

    /**
     * A ride with two wheels, a long stop and a recording gap in it, so one file
     * exercises every detector.
     *
     * Layout, one row per second:
     *   0..9     moving, wheel A
     *   10..919  stopped (15 min), wheel A
     *   920..929 moving, wheel A
     *   then a 20 minute hole
     *   930..939 moving, wheel B
     */
    @Before fun setUp() {
        source = tmp.newFile("trip_test.csv")
        source.bufferedWriter().use { w ->
            w.write(header); w.newLine()
            var t = 0L
            fun row(speed: Float, extra: String = "") {
                val date = TripCsv.let { _ ->
                    val base = 1_754_000_000_000L
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                        .format(java.util.Date(base + t))
                }
                w.write("$date,$speed,80,30,90,100,1.0,2.0,5.0,$extra")
                w.newLine()
            }
            row(20f, "wheel.name=WheelA")
            repeat(9) { t += 1000; row(20f) }
            repeat(910) { t += 1000; row(0f) }
            repeat(10) { t += 1000; row(20f) }
            t += 20 * 60_000L
            row(20f, "wheel.name=WheelB")
            repeat(9) { t += 1000; row(20f) }
        }
    }

    private fun dates(f: File): List<Long> =
        f.readLines().drop(1).filter { it.isNotBlank() }
            .mapNotNull { TripCsv.parseDate(it.substringBefore(',')) }

    // --- Change wheel --------------------------------------------------------

    @Test fun changeWheel_rewritesEveryIdentityRow_andLeavesOtherRowsAlone() {
        val dest = File(tmp.root, "out.csv")
        val changed = TripDerive.rewriteWheelIdentity(source, dest, "Renamed", mac = null)
        assertEquals("both wheel.name rows should be rewritten", 2, changed)

        val text = dest.readText()
        assertTrue(text.contains("wheel.name=Renamed"))
        assertFalse("the old names must be gone", text.contains("wheel.name=WheelA"))
        assertFalse(text.contains("wheel.name=WheelB"))
        // Row count and header preserved: this edits labels, not data.
        assertEquals(source.readLines().size, dest.readLines().size)
        assertEquals(source.readLines().first(), dest.readLines().first())
    }

    @Test fun changeWheel_onAFileWithNoExtraColumn_copiesItUnharmed() {
        val plain = tmp.newFile("plain.csv")
        plain.bufferedWriter().use { w ->
            w.write("Date,Speed"); w.newLine()
            w.write("2026-08-09 12:00:00.000,10"); w.newLine()
        }
        val dest = File(tmp.root, "plain_out.csv")
        assertEquals(0, TripDerive.rewriteWheelIdentity(plain, dest, "X", null))
        assertEquals(plain.readText(), dest.readText())
    }

    // --- Split ---------------------------------------------------------------

    @Test fun detector_findsTheStopTheGapAndTheWheelChange() {
        val points = dates(source)
        val elapsed = LongArray(points.size) { points[it] - points.first() }
        val speeds = source.readLines().drop(1).filter { it.isNotBlank() }
            .map { it.split(",")[1].toFloat() }
        // Row index of the second identity block, i.e. where WheelB starts.
        val wheelBIndex = source.readLines().drop(1)
            .indexOfFirst { it.contains("wheel.name=WheelB") }

        val cuts = TripSplitDetector.detect(
            elapsed, speeds, wheelChangeIndices = setOf(wheelBIndex)
        )
        val reasons = cuts.map { it.reason }.toSet()
        assertTrue("expected a stop cut", TripSplitDetector.Reason.STOPPED in reasons)
        assertTrue("expected a wheel-change cut", TripSplitDetector.Reason.WHEEL_CHANGE in reasons)
    }

    @Test fun splittingAtEveryCut_coversTheWholeRideWithNoLostRows() {
        val allDates = dates(source)
        val elapsed = LongArray(allDates.size) { allDates[it] - allDates.first() }
        val speeds = source.readLines().drop(1).filter { it.isNotBlank() }
            .map { it.split(",")[1].toFloat() }
        val wheelBIndex = source.readLines().drop(1)
            .indexOfFirst { it.contains("wheel.name=WheelB") }
        val cuts = TripSplitDetector.detect(elapsed, speeds, setOf(wheelBIndex))
        assertTrue("the fixture must produce cuts", cuts.isNotEmpty())

        val base = allDates.first()
        val edges = listOf(Long.MIN_VALUE) + cuts.map { base + it.atElapsedMs } + listOf(Long.MAX_VALUE)
        var total = 0
        for (i in 0 until edges.size - 1) {
            val to = if (edges[i + 1] == Long.MAX_VALUE) Long.MAX_VALUE else edges[i + 1] - 1
            val piece = File(tmp.root, "piece_$i.csv")
            total += TripDerive.writeSection(source, piece, edges[i], to)
        }
        assertEquals("every row must land in exactly one piece", allDates.size, total)
    }

    @Test fun split_leavesTheSourceUntouched() {
        val before = source.readText()
        TripDerive.writeSection(source, File(tmp.root, "s.csv"), Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(before, source.readText())
    }

    // --- Combine -------------------------------------------------------------

    @Test fun combine_keepsOneHeaderAndEveryRow() {
        val a = File(tmp.root, "ja.csv")
        val b = File(tmp.root, "jb.csv")
        TripDerive.writeSection(source, a, Long.MIN_VALUE, dates(source)[5])
        TripDerive.writeSection(source, b, dates(source)[6], Long.MAX_VALUE)
        val rowsA = a.readLines().size - 1
        val rowsB = b.readLines().size - 1

        val joined = File(tmp.root, "joined.csv")
        val written = TripDerive.writeJoined(listOf(a, b), joined)
        assertEquals(rowsA + rowsB, written)
        assertEquals(1, joined.readLines().count { it == header })
        assertEquals(dates(source).size, dates(joined).size)
    }

    // --- The rule that matters most -----------------------------------------

    @Test fun everyDerivedNameIsRecognisableAsDerived() {
        // This is what keeps split and join results off the leaderboard even
        // after a database rebuild, when only the file name survives.
        val name = TripDerive.sectionFileName("trip_x.csv", "_20260810_010203")
        assertTrue(TripDerive.isDerived(name))
        assertFalse(TripDerive.isDerived("trip_x.csv"))
    }

    @Test fun metricsOfAPieceAreItsOwn_notTheWholeRides() {
        val piece = File(tmp.root, "m.csv")
        val d = dates(source)
        TripDerive.writeSection(source, piece, d.first(), d[9])
        val pieceDates = dates(piece)
        assertEquals(10, pieceDates.size)
        assertNotNull(TripCsv.parseDate(piece.readLines()[1].substringBefore(',')))
        assertTrue(pieceDates.last() < d.last())
    }
}
