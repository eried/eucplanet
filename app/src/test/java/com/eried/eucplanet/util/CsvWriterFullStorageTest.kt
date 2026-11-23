package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.io.Writer

/**
 * A phone that runs out of storage mid-ride. The write used to throw out of
 * the recording loop and close the whole app; now the writer reports it and
 * the recording ends with what was saved.
 */
class CsvWriterFullStorageTest {

    /** Accepts [roomChars] characters, then fails every write like a full disk. */
    private class FullAfter(private val roomChars: Int) : Writer() {
        val kept = StringWriter()
        private var used = 0
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (used + len > roomChars) throw IOException("No space left on device")
            used += len
            kept.write(cbuf, off, len)
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun writerWithRoom(room: Int): Pair<CsvWriter, FullAfter> {
        val sink = FullAfter(room)
        val csv = CsvWriter(File("unused.csv")) { sink }
        csv.open()
        return csv to sink
    }

    @Test
    fun aFullDiskEndsTheRowsInsteadOfThrowing() {
        val (csv, sink) = writerWithRoom(room = 4_000)
        var firstFailure = -1
        for (i in 1..1_000) {
            if (!csv.writeRow(WheelData(speed = 20f), location = null)) {
                firstFailure = i
                break
            }
        }
        assertTrue("the disk should fill up within 1000 rows", firstFailure > 0)
        // Every later row is refused quietly, and closing does not throw.
        assertFalse(csv.writeRow(WheelData(speed = 20f), location = null))
        csv.close()
        csv.close()
        // What fit before the disk filled up is still there, header first.
        assertTrue(sink.kept.toString().startsWith("Date,Speed,"))
    }

    @Test
    fun plentyOfRoomWritesEveryRow() {
        val (csv, sink) = writerWithRoom(room = Int.MAX_VALUE)
        repeat(25) { assertTrue(csv.writeRow(WheelData(speed = 20f), location = null)) }
        csv.close()
        assertEquals(25, csv.rows)
        assertEquals(26, sink.kept.toString().trimEnd().lines().size)
    }
}
