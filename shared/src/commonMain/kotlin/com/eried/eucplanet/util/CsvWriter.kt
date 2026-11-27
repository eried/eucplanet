package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.platform.Locale
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.platform.formatLocalDateUs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Where [CsvWriter] puts its lines: a file, opened on the platform's terms. */
interface TextSink {
    /**
     * Creates or truncates the file. Throws when it cannot. [writeLine] and
     * [flush] throw when the file cannot take more (the phone is out of
     * storage); [CsvWriter] turns that into a refused row.
     */
    fun open()

    /**
     * Opens the existing file to add lines after its last one. Only a sink
     * that can carry on a file has it (the iPhone's, for a trip resumed after
     * a Bluetooth relaunch); the others refuse rather than truncate.
     */
    fun openAppend(): Unit = throw UnsupportedOperationException("this sink cannot append")
    fun writeLine(line: String)
    fun flush()
    fun close()
}

/**
 * Writes DarknessBot-compatible CSV files.
 * Format: `Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra`
 * The trailing GPS-speed, Current, PWM, G-Force and Extra columns are EUC
 * Planet extensions; DarknessBot viewers ignore trailing columns. `GPS speed`
 * carries the external BLE GPS box's reading when the rider prioritises
 * external GPS, otherwise the phone's own GPS speed. `Current` (amps, signed)
 * and `PWM` (percent) come straight from the wheel telemetry.
 *
 * `Extra` is an event channel: empty on normal rows, one `key=value` pair on
 * event rows (events with several keys spill onto consecutive rows). The
 * viewer reads the `wheel.*` keys for per-wheel attribution; the namespace is
 * open for future events. Values are sanitised so a BLE name can never break
 * the CSV framing.
 */
class CsvWriter(private val sink: TextSink) {

    private var writer: TextSink? = null
    private var rowCount = 0

    // writeRow runs on the recording loop while close() comes from whoever
    // stops the trip, so both hold this lock: a close can never land in the
    // middle of a row.
    private val lock = SynchronizedObject()

    /** Number of data rows written so far (excluding the header). */
    val rows: Int get() = rowCount

    /**
     * Creates the file and writes the header through to disk. Throws when that
     * fails, with nothing left open: on a full phone the start then fails
     * cleanly, instead of looking fine until the first rows are flushed.
     */
    fun open() {
        sink.open()
        try {
            sink.writeLine("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y,Torque,Phase current,Extra")
            sink.flush()
        } catch (e: Exception) {
            runCatching { sink.close() }
            throw e
        }
        synchronized(lock) { writer = sink }
    }

    /**
     * Carries on a file that already holds its header and [rowsSoFar] data
     * rows, as the trip killed in the background did: no second header, and
     * the row count goes on from there.
     */
    fun resume(rowsSoFar: Int) {
        sink.openAppend()
        synchronized(lock) {
            writer = sink
            rowCount = rowsSoFar.coerceAtLeast(0)
        }
    }

    /**
     * Appends one row. Returns false when the row could not be written, which
     * in practice means the phone ran out of storage: the file is then closed
     * with what it already holds, and every later call returns false. The
     * caller stops the recording; an exception here used to escape the
     * recording loop and close the whole app mid-ride.
     */
    fun writeRow(data: WheelData, location: PhoneLocation?, externalGpsSpeedKmh: Float? = null, wheelConnected: Boolean = true, extra: String? = null): Boolean =
        synchronized(lock) {
            val w = writer ?: return@synchronized false
            try {
                writeRowTo(w, data, location, externalGpsSpeedKmh, wheelConnected, extra)
                true
            } catch (e: Exception) {
                Log.e("CsvWriter", "Trip row write failed, closing the file", e)
                writer = null
                runCatching { w.close() }
                false
            }
        }

    private fun writeRowTo(w: TextSink, data: WheelData, location: PhoneLocation?, externalGpsSpeedKmh: Float?, wheelConnected: Boolean, extra: String?) {
        // Use wallclock so rows recorded without wheel telemetry (disconnected or
        // never connected) still carry a correct date. WheelData.timestamp defaults to
        // app-start time when no packet has arrived, which would freeze every row.
        val nowMs = System.currentTimeMillis()
        val tsMs = if (data.timestamp in (nowMs - 2000L)..nowMs) data.timestamp else nowMs
        val date = formatLocalDateUs("dd.MM.yyyy HH:mm:ss.SSS", tsMs)
        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val alt = location?.altitude ?: 0.0

        val phoneGpsKmh = if (location?.hasSpeed() == true) location.speed * 3.6f else 0f
        // Prefer the wheel's own speed whenever it's connected -- 0 is a valid
        // "stopped" reading, so don't treat it as "no data". Only fall back to
        // GPS speed when NO wheel is connected; otherwise indoor / walking-pace
        // GPS noise (e.g. 28 km/h while wheeling it across a room) leaks into the
        // speed-of-record column.
        val speed = if (wheelConnected) data.speed else phoneGpsKmh
        // GPS speed column: the external box's speed when the rider prioritises
        // external GPS (passed in non-null), otherwise the phone's GPS speed.
        val gpsSpeedKmh = externalGpsSpeedKmh ?: phoneGpsKmh

        // The Extra cell must never contain CSV framing characters; BLE names
        // are rider-controlled, so scrub rather than quote.
        val extraCell = extra?.replace(Regex("[,\"\\r\\n]+"), " ")?.trim() ?: ""

        w.writeLine(
            String.format(
                Locale.US,
                // Column precision follows what the source actually
                // resolves, so the file never rounds real data away: the
                // wheel reports speed / voltage / temperature / current /
                // PWM / phase in hundredths and the odometer in thousandths
                // of a km. GPS-derived columns (altitude, GPS speed) stay at
                // one decimal - beyond that is receiver noise, not data.
                "%s,%.2f,%.2f,%.2f,%d,%.1f,%.6f,%.6f,%.3f,%.1f,%.2f,%.2f,%.3f,%.3f,%.3f,%.2f,%.2f,%s",
                date,
                speed,
                data.voltage,
                data.maxTemperature,
                data.batteryPercent,
                alt,
                lat,
                lon,
                data.totalDistance,
                gpsSpeedKmh,
                data.current,
                data.pwm,
                data.gForce,
                data.accelX,
                data.accelY,
                // Torque (Nm) and phase current (A), both signed. Families
                // that never report them write zeros; the trip screens treat
                // an all-zero column as "not recorded".
                data.torque,
                data.phaseCurrent,
                extraCell
            )
        )
        rowCount++

        // Flush every 10 rows
        if (rowCount % 10 == 0) {
            w.flush()
        }
    }

    /** Flushes and closes. Safe to call twice, and never throws on a full disk. */
    fun close() {
        synchronized(lock) {
            val w = writer ?: return
            writer = null
            try {
                w.flush()
            } catch (e: Exception) {
                Log.e("CsvWriter", "Trip flush on close failed", e)
            }
            runCatching { w.close() }
        }
    }
}
