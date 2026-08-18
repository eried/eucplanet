package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripDerive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TripDeriveTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = "Date,Speed,Voltage,Temperature,Battery,Altitude,Latitude,Longitude,Total mileage"

    /** One row per second from 12:00:00, with an unusual column count preserved. */
    private fun csv(name: String, count: Int, startSec: Int = 0): File {
        val f = tmp.newFile(name)
        f.bufferedWriter().use { w ->
            w.write(header); w.newLine()
            for (i in 0 until count) {
                w.write("2026-08-09 12:00:%02d.000,10,80,30,90,100,1.0,2.0,5.${i}".format(startSec + i))
                w.newLine()
            }
        }
        return f
    }

    private fun epoch(sec: Int): Long =
        com.eried.eucplanet.util.TripCsv.parseDate("2026-08-09 12:00:%02d.000".format(sec))!!

    @Test fun derivedMarker_isRecognisedInAFileName() {
        assertTrue(TripDerive.isDerived("trip_1${TripDerive.DERIVED_MARKER}_x.csv"))
        assertFalse(TripDerive.isDerived("trip_1.csv"))
    }

    @Test fun sectionFileName_keepsTheMarkerAndTheCsvSuffix() {
        val n = TripDerive.sectionFileName("trip_2026.csv", "_20260809_120000")
        assertTrue(TripDerive.isDerived(n))
        assertTrue(n.endsWith(".csv"))
    }

    @Test fun writeSection_keepsOnlyRowsInsideTheRange() {
        val src = csv("src.csv", 10)
        val dest = File(tmp.root, "out.csv")
        val rows = TripDerive.writeSection(src, dest, epoch(3), epoch(5))
        assertEquals(3, rows)
        val lines = dest.readLines().filter { it.isNotBlank() }
        assertEquals(header, lines.first())
        assertEquals(4, lines.size)
        assertTrue(lines[1].startsWith("2026-08-09 12:00:03"))
        assertTrue(lines[3].startsWith("2026-08-09 12:00:05"))
    }

    @Test fun writeSection_copiesRowsVerbatimSoUnknownColumnsSurvive() {
        val src = tmp.newFile("wide.csv")
        src.bufferedWriter().use { w ->
            w.write("$header,SomethingWeDoNotModel"); w.newLine()
            w.write("2026-08-09 12:00:00.000,10,80,30,90,100,1.0,2.0,5.0,keepme"); w.newLine()
        }
        val dest = File(tmp.root, "wide_out.csv")
        assertEquals(1, TripDerive.writeSection(src, dest, epoch(0), epoch(1)))
        assertTrue(dest.readText().contains("keepme"))
        assertTrue(dest.readLines().first().endsWith("SomethingWeDoNotModel"))
    }

    @Test fun writeSection_withNoMatchingRows_writesNothingAndReportsZero() {
        val src = csv("src2.csv", 3)
        val dest = File(tmp.root, "empty.csv")
        assertEquals(0, TripDerive.writeSection(src, dest, epoch(40), epoch(50)))
    }

    @Test fun writeSection_onAMissingSource_isZeroNotACrash() {
        assertEquals(
            0,
            TripDerive.writeSection(File(tmp.root, "nope.csv"), File(tmp.root, "o.csv"), 0, 1)
        )
    }

    @Test fun writeJoined_concatenatesWithASingleHeader() {
        val a = csv("a.csv", 3, startSec = 0)
        val b = csv("b.csv", 2, startSec = 10)
        val dest = File(tmp.root, "joined.csv")
        assertEquals(5, TripDerive.writeJoined(listOf(a, b), dest))
        val lines = dest.readLines().filter { it.isNotBlank() }
        assertEquals(6, lines.size)
        assertEquals(header, lines.first())
        assertEquals(1, lines.count { it == header })
    }

    @Test fun writeJoined_skipsMissingFiles() {
        val a = csv("a2.csv", 2)
        val dest = File(tmp.root, "joined2.csv")
        assertEquals(2, TripDerive.writeJoined(listOf(a, File(tmp.root, "gone.csv")), dest))
    }

    @Test fun writeJoined_withNothingUsable_isZero() {
        val dest = File(tmp.root, "joined3.csv")
        assertEquals(0, TripDerive.writeJoined(listOf(File(tmp.root, "gone.csv")), dest))
    }

    // --- rewriteTripName (custom trip names, eucviewer-compatible) ---

    private val headerExtra = "$header,Extra"

    /** A CSV with an Extra column; [extras] is the Extra cell for each row. */
    private fun extraCsv(name: String, extras: List<String>): File {
        val f = tmp.newFile(name)
        f.bufferedWriter().use { w ->
            w.write(headerExtra); w.newLine()
            extras.forEachIndexed { i, ex ->
                w.write("2026-08-09 12:00:%02d.000,10,80,30,90,100,1.0,2.0,5.$i,$ex".format(i))
                w.newLine()
            }
        }
        return f
    }

    private fun tripNameCount(text: String) =
        Regex("trip\\.name=", RegexOption.IGNORE_CASE).findAll(text).count()

    @Test fun rewriteTripName_writesTheNameIntoTheFirstEmptyExtraCell() {
        val src = extraCsv("n1.csv", listOf("", "wheel.name=V14", ""))
        val dest = File(tmp.root, "n1_out.csv")
        assertEquals(1, TripDerive.rewriteTripName(src, dest, "Morning commute"))
        val text = dest.readText()
        assertTrue(text.contains("trip.name=Morning commute")) // exact form eucviewer reads
        assertEquals(1, tripNameCount(text))
        assertTrue(text.contains("wheel.name=V14"))            // wheel identity untouched
    }

    @Test fun rewriteTripName_replacesAnExistingNameSoOnlyOneRemains() {
        val src = extraCsv("n2.csv", listOf("trip.name=Old", "", ""))
        val dest = File(tmp.root, "n2_out.csv")
        TripDerive.rewriteTripName(src, dest, "New name")
        val text = dest.readText()
        assertTrue(text.contains("trip.name=New name"))
        assertFalse(text.contains("trip.name=Old"))
        assertEquals(1, tripNameCount(text))
    }

    @Test fun rewriteTripName_blankClearsTheName() {
        val src = extraCsv("n3.csv", listOf("trip.name=Old", ""))
        val dest = File(tmp.root, "n3_out.csv")
        TripDerive.rewriteTripName(src, dest, "")
        assertEquals(0, tripNameCount(dest.readText()))
    }

    @Test fun rewriteTripName_withNoExtraColumn_isZeroAndSafe() {
        val src = csv("n4.csv", 2) // header carries no Extra column
        val dest = File(tmp.root, "n4_out.csv")
        assertEquals(0, TripDerive.rewriteTripName(src, dest, "Whatever"))
    }

    @Test fun rewriteTripName_sanitisesCommasSoFramingSurvives() {
        val src = extraCsv("n5.csv", listOf("", ""))
        val dest = File(tmp.root, "n5_out.csv")
        TripDerive.rewriteTripName(src, dest, "A, B, C")
        val lines = dest.readLines().filter { it.isNotBlank() }
        val cols = lines.first().split(",").size
        lines.drop(1).forEach { assertEquals(cols, it.split(",").size) } // no extra columns
        assertTrue(dest.readText().contains("trip.name=A B C"))
    }
}
