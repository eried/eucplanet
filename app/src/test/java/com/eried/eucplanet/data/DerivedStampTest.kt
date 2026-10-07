package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripDerive
import com.eried.eucplanet.data.repository.WheelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A derived file - a split piece, a join - has to describe itself.
 *
 * Found on the emulator with real files: the recorder writes the wheel once,
 * near the top, so the piece cut from the second half of a ride carried no
 * wheel at all and eucviewer filed it as a generic wheel. And both pieces
 * inherited whatever trip.name= row they happened to contain - the file said
 * "test 3" to eucviewer while the app listed the piece by its date.
 */
class DerivedStampTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = "date,speed,voltage,current,power,battery,latitude,longitude,mileage,extra"

    private fun row(sec: Int, extra: String = "") =
        "2026-02-01 10:00:%02d.000,10,80,1,80,90,52.1,4.3,100,%s".format(sec, extra)

    private fun source(): File = tmp.newFile().apply {
        writeText(listOf(header,
            row(0, "wheel.name=Adventure-E0000298"), row(1, "wheel.mac=F4E02AB02A75"),
            row(2, "wheel.brand=InMotion"), row(3, "wheel.model=InMotion V14 50GB"),
            row(4, "trip.name=test 3"), row(5), row(6), row(7), row(8), row(9),
        ).joinToString("\n") + "\n")
    }

    private fun identity() = WheelChoice(name = "Adventure-E0000298", mac = "F4E02AB02A75",
        brand = "InMotion", model = "InMotion V14 50GB").extraFields()

    private fun pairs(f: File) = f.readLines().drop(1)
        .map { it.split(",").last().trim() }.filter { it.contains('=') }

    @Test fun `the second half of a ride still knows its wheel`() {
        val src = source()
        val piece = tmp.newFile()
        // Rows 6..9 only: none of the identity rows are in this range.
        val t6 = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse("2026-02-01 10:00:06.000")!!.time
        TripDerive.writeSection(src, piece, t6, Long.MAX_VALUE)
        assertTrue("the cut should have lost the identity", pairs(piece).none { it.startsWith("wheel.") })

        assertTrue(TripDerive.stampDerived(piece, identity()))
        val p = pairs(piece)
        for (line in listOf("wheel.name=Adventure-E0000298", "wheel.mac=F4E02AB02A75",
                "wheel.brand=InMotion", "wheel.model=InMotion V14 50GB")) {
            assertTrue("piece lacks $line", line in p)
        }
        assertEquals("rows are not added, only free cells filled", 4, piece.readLines().size - 1)
    }

    @Test fun `a derived file does not inherit the source's name`() {
        val src = source()
        val piece = tmp.newFile()
        TripDerive.writeSection(src, piece, Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue(pairs(piece).any { it.startsWith("trip.name=") })
        TripDerive.stampDerived(piece, identity())
        assertTrue("the source's name survived into the piece",
            pairs(piece).none { it.startsWith("trip.name=") })
    }

    @Test fun `a join carries one identity, not a stale second block`() {
        val a = source()
        val b = tmp.newFile().apply {
            writeText(listOf(header, row(20, "wheel.name=Other"), row(21, "wheel.model=Lynx"), row(22))
                .joinToString("\n") + "\n")
        }
        val join = tmp.newFile()
        TripDerive.writeJoined(listOf(a, b), join)
        TripDerive.stampDerived(join, identity())
        val p = pairs(join)
        assertTrue("the second source's wheel survived the join", "wheel.name=Other" !in p && "wheel.model=Lynx" !in p)
        assertEquals("every name row should say the same wheel",
            setOf("wheel.name=Adventure-E0000298"), p.filter { it.startsWith("wheel.name=") }.toSet())
    }

    @Test fun `without an identity the file still loses the inherited name`() {
        val src = source()
        val piece = tmp.newFile()
        TripDerive.writeSection(src, piece, Long.MIN_VALUE, Long.MAX_VALUE)
        assertTrue(TripDerive.stampDerived(piece, emptyMap()))
        assertTrue(pairs(piece).none { it.startsWith("trip.name=") })
    }
}
