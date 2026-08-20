package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripDerive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Changing a trip's wheel has to reach the file, not just the database row.
 *
 * The CSV is what travels: it is what gets shared, what lands on Dropbox, what
 * eucviewer reads, and what comes back if the app is reinstalled. An edit that
 * only updates the row looks right in the app and is gone the moment the trip
 * leaves it - which is what happened to a GPS-only ride, whose file carries no
 * wheel identity to overwrite.
 */
class WheelIdentityRewriteTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header =
        "Date,Speed,Voltage,Temperature,Battery,Altitude,Latitude,Longitude,Total mileage,Extra"

    /** [extras] gives the Extra cell for each row, in order. */
    private fun csv(name: String, extras: List<String>): File {
        val f = tmp.newFile(name)
        f.bufferedWriter().use { w ->
            w.write(header); w.newLine()
            extras.forEachIndexed { i, extra ->
                w.write("2026-08-21 01:00:%02d.000,10,80,30,90,100,1.0,2.0,5.0,%s".format(i, extra))
                w.newLine()
            }
        }
        return f
    }

    private fun extrasOf(f: File): List<String> =
        f.readLines().drop(1).map { it.split(",").last().trim() }

    private fun rewrite(src: File, name: String, mac: String?): Pair<Int, File> {
        val dest = File(tmp.root, src.name + ".out")
        val n = TripDerive.rewriteWheelIdentity(src, dest, name, mac)
        return n to dest
    }

    // --- the case that was broken -----------------------------------------

    @Test fun `a ride with no wheel recorded gets the wheel written into the file`() {
        val src = csv("gps_only.csv", listOf("", "", "", ""))
        val (changed, out) = rewrite(src, "Veteran Sherman L", null)
        assertTrue("nothing was written to the file", changed > 0)
        assertTrue(extrasOf(out).any { it == "wheel.name=Veteran Sherman L" })
    }

    @Test fun `the mac is written too when it is known`() {
        val src = csv("gps_only.csv", listOf("", "", ""))
        val (_, out) = rewrite(src, "Sherman", "aa:bb:cc:dd:ee:ff")
        val extras = extrasOf(out)
        assertTrue(extras.any { it == "wheel.name=Sherman" })
        assertTrue(extras.any { it == "wheel.mac=AABBCCDDEEFF" })
    }

    @Test fun `the wheel goes in beside an existing trip name, not over it`() {
        val src = csv("named.csv", listOf("trip.name=Evening loop", "", ""))
        val (_, out) = rewrite(src, "Sherman", null)
        val extras = extrasOf(out)
        assertTrue("the trip name was clobbered", extras.contains("trip.name=Evening loop"))
        assertTrue(extras.any { it == "wheel.name=Sherman" })
    }

    // --- the case that already worked --------------------------------------

    @Test fun `an existing wheel name is corrected in place`() {
        val src = csv("had_wheel.csv", listOf("wheel.name=Old Wheel", "", ""))
        val (_, out) = rewrite(src, "New Wheel", null)
        val extras = extrasOf(out)
        assertTrue(extras.contains("wheel.name=New Wheel"))
        assertTrue("the old name is still in the file", extras.none { it.contains("Old Wheel") })
    }

    @Test fun `every row that names the wheel is corrected`() {
        val src = csv("repeated.csv", listOf("wheel.name=Old", "", "wheel.name=Old"))
        val (changed, out) = rewrite(src, "New", null)
        assertEquals(2, changed)
        assertEquals(2, extrasOf(out).count { it == "wheel.name=New" })
    }

    @Test fun `an existing mac is corrected and normalised`() {
        val src = csv("had_mac.csv", listOf("wheel.mac=001122334455", ""))
        val (_, out) = rewrite(src, "Sherman", "66-77-88-99-aa-bb")
        assertTrue(extrasOf(out).contains("wheel.mac=66778899AABB"))
    }

    // --- keeping the file readable ----------------------------------------

    @Test fun `a name with commas or quotes cannot break the row`() {
        val src = csv("gps_only.csv", listOf("", ""))
        val (_, out) = rewrite(src, "Begode, \"EX30\"\nbeta", null)
        val columns = out.readLines().drop(1).map { it.split(",").size }
        assertTrue("a row gained columns", columns.all { it == columns.first() })
        assertTrue(extrasOf(out).any { it.startsWith("wheel.name=") && "," !in it })
    }

    @Test fun `a file with no Extra column is left alone`() {
        val f = tmp.newFile("foreign.csv")
        f.bufferedWriter().use { w ->
            w.write("Date,Speed,Voltage"); w.newLine()
            w.write("2026-08-21 01:00:00.000,10,80"); w.newLine()
        }
        val dest = File(tmp.root, "foreign.out")
        assertEquals(0, TripDerive.rewriteWheelIdentity(f, dest, "Sherman", null))
    }

    @Test fun `the wheel is written once, not into every free row`() {
        val src = csv("gps_only.csv", listOf("", "", "", "", ""))
        val (_, out) = rewrite(src, "Sherman", null)
        assertEquals(1, extrasOf(out).count { it.startsWith("wheel.name=") })
    }

    @Test fun `rows that carry nothing else are untouched`() {
        val src = csv("gps_only.csv", listOf("", "", ""))
        val (_, out) = rewrite(src, "Sherman", null)
        val rows = out.readLines().drop(1)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.startsWith("2026-08-21 01:00:") })
    }
}
