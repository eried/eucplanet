package com.eried.eucplanet.ui.recording

import com.eried.eucplanet.util.TripCsv

/**
 * Turns the rows of a trip CSV into [TripDataPoint]s. One per file: the columns
 * are found once, from the header, and every row is read against them.
 *
 * A row stands by itself, nothing carries from one to the next, which is what
 * lets [TripTail] read a recording in pieces.
 */
class TripCsvPoints(headerLine: String) {
    private val headers = headerLine.lowercase().split(",").map { it.trim() }

    // Format-tolerant column resolution (canonical EUC Planet /
    // DarknessBot names first, EUC World aliases after) lives in
    // TripCsv.Columns so every trip-CSV path resolves identically.
    private val iSpeed = TripCsv.Columns.speed(headers).takeIf { it >= 0 } ?: 1
    private val iVoltage = TripCsv.Columns.voltage(headers).takeIf { it >= 0 } ?: 2
    private val iTemp = TripCsv.Columns.temperature(headers).takeIf { it >= 0 } ?: 3
    private val iBattery = TripCsv.Columns.battery(headers).takeIf { it >= 0 } ?: 4
    private val iAltitude = TripCsv.Columns.altitude(headers).takeIf { it >= 0 } ?: 5
    private val iLat = TripCsv.Columns.latitude(headers).takeIf { it >= 0 } ?: 6
    private val iLon = TripCsv.Columns.longitude(headers).takeIf { it >= 0 } ?: 7
    private val iMileage = TripCsv.Columns.mileage(headers).takeIf { it >= 0 } ?: 8
    // -1 when the column is absent (older trip files / foreign exports).
    private val iGpsSpeed = TripCsv.Columns.gpsSpeed(headers)
    private val iExtGps = headers.indexOfFirst { it.startsWith("ext gps") || it.startsWith("ext_gps") || it == "external gps speed" }
    // -1 when absent, like gpsSpeed.
    private val iCurrent = TripCsv.Columns.current(headers)
    private val iPwm = TripCsv.Columns.pwm(headers)
    private val iTorque = TripCsv.Columns.torque(headers)
    private val iPhase = TripCsv.Columns.phaseCurrent(headers)
    private val iExtra = headers.indexOfFirst { it == "extra" }
    private val minColumns = maxOf(iSpeed, iVoltage, iTemp, iBattery)

    /** The point of one row, or null for a row that is short or does not parse. */
    fun parse(line: String): TripDataPoint? {
        try {
            val parts = line.split(",")
            if (parts.size <= minColumns) return null
            val extRaw = if (iExtGps >= 0) parts.getOrNull(iExtGps)?.trim() else null
            val extSpeed = extRaw
                ?.takeIf { it.isNotEmpty() }
                ?.toFloatOrNull()
                ?: Float.NaN
            // Current / PWM are EUC Planet extensions; absent column or
            // blank cell both map to NaN ("not recorded").
            val current = if (iCurrent >= 0)
                parts.getOrNull(iCurrent)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
            else Float.NaN
            val pwm = if (iPwm >= 0)
                parts.getOrNull(iPwm)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
            else Float.NaN
            val torque = if (iTorque >= 0)
                parts.getOrNull(iTorque)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
            else Float.NaN
            val phase = if (iPhase >= 0)
                parts.getOrNull(iPhase)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: Float.NaN
            else Float.NaN
            return TripDataPoint(
                date = parts[0],
                speed = parts.getOrNull(iSpeed)?.toFloatOrNull() ?: 0f,
                voltage = parts.getOrNull(iVoltage)?.toFloatOrNull() ?: 0f,
                temperature = parts.getOrNull(iTemp)?.toFloatOrNull() ?: 0f,
                battery = parts.getOrNull(iBattery)?.toFloatOrNull()?.toInt() ?: 0,
                altitude = parts.getOrNull(iAltitude)?.toFloatOrNull() ?: 0f,
                latitude = parts.getOrNull(iLat)?.toDoubleOrNull() ?: 0.0,
                longitude = parts.getOrNull(iLon)?.toDoubleOrNull() ?: 0.0,
                totalMileage = parts.getOrNull(iMileage)?.toFloatOrNull() ?: 0f,
                gpsSpeed = if (iGpsSpeed >= 0) parts.getOrNull(iGpsSpeed)?.toFloatOrNull() ?: 0f else 0f,
                extGpsSpeed = extSpeed,
                current = current,
                pwm = pwm,
                torque = torque,
                phaseCurrent = phase,
                extra = if (iExtra >= 0) parts.getOrNull(iExtra)?.trim() ?: "" else ""
            )
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        /** Every row of a trip's [lines], header first. Empty when there is no header. */
        fun readAll(lines: Sequence<String>): List<TripDataPoint> {
            val it = lines.iterator()
            if (!it.hasNext()) return emptyList()
            val rows = TripCsvPoints(it.next())
            val points = mutableListOf<TripDataPoint>()
            for (line in it) rows.parse(line)?.let { p -> points.add(p) }
            return points
        }
    }
}

/**
 * A trip file opened for reading at any position: RandomAccessFile on Android,
 * NSFileHandle on the iPhone. [TripTail] closes it.
 */
interface TripFileRead {
    val length: Long
    /** [count] bytes from [at]. */
    fun read(at: Long, count: Int): ByteArray
    fun close()
}

/**
 * A recording being read while it grows.
 *
 * The live trip screen refreshes every few seconds. It used to read and parse
 * the whole file each time, so the refresh got slower the longer the ride: two
 * hours in, more than seven thousand rows, twenty times a minute. A tail keeps
 * where the last read ended and the next read takes only the rows after it.
 *
 * Only whole rows are taken: a row the recorder is in the middle of writing is
 * left for the next read. And the file has to be the one the last read saw. If
 * it is shorter, or the bytes just before the old end changed (a trip tool
 * rewrote it), the whole file is read again.
 */
class TripTail private constructor(
    val points: List<TripDataPoint>,
    private val rows: TripCsvPoints?,
    private val offset: Long,
    private val guard: ByteArray,
) {
    /**
     * The same recording a moment later. Returns this very object when nothing
     * was added. [open] gives the file, or null when it is gone.
     */
    fun advance(open: () -> TripFileRead?): TripTail {
        val parser = rows ?: return read(open)
        val raf = open() ?: return EMPTY
        try {
            val length = raf.length
            if (length < offset || !sameFile(raf)) return read(open)
            if (length == offset) return this
            val fresh = raf.read(offset, (length - offset).toInt())
            val lastNewline = fresh.lastIndexOf(NEWLINE)
            if (lastNewline < 0) return this
            val whole = fresh.copyOf(lastNewline + 1)
            val added = ArrayList<TripDataPoint>()
            for (line in whole.decodeToString().lineSequence()) {
                if (line.isEmpty()) continue
                parser.parse(line)?.let { added.add(it) }
            }
            val end = offset + whole.size
            return TripTail(points + added, parser, end, guardAt(raf, end))
        } finally {
            raf.close()
        }
    }

    private fun sameFile(raf: TripFileRead): Boolean =
        guard.contentEquals(guardAt(raf, offset))

    companion object {
        private const val NEWLINE = '\n'.code.toByte()
        private const val GUARD_BYTES = 64
        private val EMPTY = TripTail(emptyList(), null, 0L, ByteArray(0))

        /** The bytes just before [end]: enough to notice a file that was rewritten. */
        private fun guardAt(raf: TripFileRead, end: Long): ByteArray {
            val n = minOf(GUARD_BYTES.toLong(), end).toInt()
            return raf.read(end - n, n)
        }

        /** A first, whole read of the file [open] gives. */
        fun read(open: () -> TripFileRead?): TripTail {
            val raf = open() ?: return EMPTY
            try {
                val all = raf.read(0L, raf.length.toInt())
                val lastNewline = all.lastIndexOf(NEWLINE)
                if (lastNewline < 0) return EMPTY
                val text = all.decodeToString(0, lastNewline + 1)
                val lines = text.lineSequence().iterator()
                if (!lines.hasNext()) return EMPTY
                val parser = TripCsvPoints(lines.next())
                val points = ArrayList<TripDataPoint>()
                for (line in lines) {
                    if (line.isEmpty()) continue
                    parser.parse(line)?.let { points.add(it) }
                }
                val end = (lastNewline + 1).toLong()
                return TripTail(points, parser, end, guardAt(raf, end))
            } finally {
                raf.close()
            }
        }
    }
}
