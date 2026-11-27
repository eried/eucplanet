package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.WheelData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A phone that runs out of storage mid-ride. The write used to throw out of
 * the recording loop and close the whole app; now the writer reports it and
 * the recording ends with what was saved.
 */
class CsvWriterFullStorageTest {

    /** Accepts [roomChars] characters, then fails every write like a full disk. */
    private class FullAfter(private val roomChars: Int) : TextSink {
        val kept = StringBuilder()
        private var used = 0
        override fun open() {}
        override fun writeLine(line: String) {
            val len = line.length + 1
            if (used.toLong() + len > roomChars) throw IllegalStateException("No space left on device")
            used += len
            kept.append(line).append('\n')
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun writerWithRoom(room: Int): Pair<CsvWriter, FullAfter> {
        val sink = FullAfter(room)
        val csv = CsvWriter(sink)
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
        assertTrue(firstFailure > 0, "the disk should fill up within 1000 rows")
        // Every later row is refused quietly, and closing does not throw.
        assertFalse(csv.writeRow(WheelData(speed = 20f), location = null))
        csv.close()
        csv.close()
        // What fit before the disk filled up is still there, header first.
        assertTrue(sink.kept.toString().startsWith("Date,Speed,"))
    }

    @Test
    fun aFullDiskFailsTheOpenAndLeavesNothingOpen() {
        // The header goes to disk at open, so a phone with no room fails the
        // start instead of looking fine until the first rows are flushed.
        val csv = CsvWriter(FullAfter(roomChars = 0))
        // The caller aborts the recording.
        assertFailsWith<IllegalStateException> { csv.open() }
        assertFalse(csv.writeRow(WheelData(speed = 20f), location = null))
        csv.close()
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
