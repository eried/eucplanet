package com.eried.eucplanet.ui.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TripTailTest {

    @get:Rule val tmp = TemporaryFolder()

    private val header = "Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,Extra"

    private fun row(i: Int) =
        "2026-11-25 10:%02d:%02d.000,%d.5,84.0,31.0,77,12.0,59.9,10.7,%d.0,0.0,3.2,12.0,".format(i / 60, i % 60, i, 100 + i)

    private fun fileWith(rows: Int): File = tmp.newFile().apply {
        writeText(header + "\n" + (0 until rows).joinToString("") { row(it) + "\n" })
    }

    @Test
    fun `reading further gives exactly what reading the whole file gives`() {
        val f = fileWith(50)
        var tail = TripTail.read(f)
        assertEquals(TripCsvPoints.readAll(f), tail.points)
        for (i in 50 until 400) {
            f.appendText(row(i) + "\n")
            if (i % 7 == 0) {
                tail = tail.advance(f)
                assertEquals(TripCsvPoints.readAll(f), tail.points)
            }
        }
        tail = tail.advance(f)
        assertEquals(400, tail.points.size)
        assertEquals(TripCsvPoints.readAll(f), tail.points)
    }

    @Test
    fun `a row still being written waits for the next read`() {
        val f = fileWith(10)
        val tail = TripTail.read(f)
        val line = row(10)
        f.appendText(line.substring(0, 30))
        val mid = tail.advance(f)
        assertSame("half a row must change nothing", tail, mid)
        f.appendText(line.substring(30) + "\n")
        val after = mid.advance(f)
        assertEquals(11, after.points.size)
        assertEquals(TripCsvPoints.readAll(f), after.points)
    }

    @Test
    fun `nothing new gives the same object back, new rows a new list`() {
        val f = fileWith(10)
        val tail = TripTail.read(f)
        assertSame(tail, tail.advance(f))
        f.appendText(row(10) + "\n")
        val next = tail.advance(f)
        assertNotSame(tail.points, next.points)
        assertEquals(10, tail.points.size)
    }

    @Test
    fun `a file a trip tool made shorter is read again from the start`() {
        val f = fileWith(100)
        val tail = TripTail.read(f)
        f.writeText(header + "\n" + (20 until 60).joinToString("") { row(it) + "\n" })
        val after = tail.advance(f)
        assertEquals(40, after.points.size)
        assertEquals(TripCsvPoints.readAll(f), after.points)
    }

    @Test
    fun `a file rewritten to the same length or longer is read again too`() {
        val f = fileWith(100)
        val tail = TripTail.read(f)
        // Same number of rows, other content: a heal that rewrote the stamps.
        f.writeText(header + "\n" + (500 until 600).joinToString("") { row(it) + "\n" })
        f.appendText(row(600) + "\n")
        val after = tail.advance(f)
        assertEquals(TripCsvPoints.readAll(f), after.points)
        assertEquals(101, after.points.size)
    }

    @Test
    fun `a missing or empty file is an empty trip, and it picks up when rows come`() {
        val missing = File(tmp.root, "none.csv")
        assertTrue(TripTail.read(missing).points.isEmpty())
        val f = tmp.newFile()
        var tail = TripTail.read(f)
        assertTrue(tail.points.isEmpty())
        f.writeText(header + "\n" + row(0) + "\n")
        tail = tail.advance(f)
        assertEquals(1, tail.points.size)
    }

    @Test
    fun `speed of a refresh late in a long ride, whole file against the tail`() {
        // Not a pass or fail on time. Prints what one 3 second refresh costs
        // two hours into a ride at one row a second.
        val f = fileWith(7200)
        var tail = TripTail.read(f)
        fun micros(block: () -> Unit): Double {
            repeat(5) { block() }
            val t0 = System.nanoTime()
            repeat(20) { block() }
            return (System.nanoTime() - t0) / 20 / 1000.0
        }
        val whole = micros { TripCsvPoints.readAll(f) }
        var i = 7200
        val further = micros {
            repeat(3) { f.appendText(row(i++ % 3600) + "\n") }
            tail = tail.advance(f)
        }
        println("TAILBENCH whole=%.0f us tail=%.0f us per refresh at 7200 rows".format(whole, further))
        assertEquals(TripCsvPoints.readAll(f).size, tail.points.size)
    }
}
