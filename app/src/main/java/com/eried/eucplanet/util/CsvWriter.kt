package com.eried.eucplanet.util

import android.location.Location
import com.eried.eucplanet.data.model.WheelData
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes DarknessBot-compatible CSV files.
 * Format: `Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y`
 * The trailing GPS-speed, Current, PWM and G-Force columns are EUC Planet
 * extensions; DarknessBot viewers ignore trailing columns. `GPS speed` carries
 * the external BLE GPS box's reading when the rider prioritises external GPS,
 * otherwise the phone's own GPS speed. `Current` (amps, signed) and `PWM`
 * (percent) come straight from the wheel telemetry.
 */
class CsvWriter(private val file: File) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var rowCount = 0

    /** Number of data rows written so far (excluding the header). */
    val rows: Int get() = rowCount

    fun open() {
        writer = BufferedWriter(FileWriter(file))
        writer?.write("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed,Current,PWM,G-Force,G-Force X,G-Force Y")
        writer?.newLine()
    }

    fun writeRow(data: WheelData, location: Location?, externalGpsSpeedKmh: Float? = null, wheelConnected: Boolean = true) {
        val w = writer ?: return
        // Use wallclock so rows recorded without wheel telemetry (disconnected or
        // never connected) still carry a correct date. WheelData.timestamp defaults to
        // app-start time when no packet has arrived, which would freeze every row.
        val nowMs = System.currentTimeMillis()
        val tsMs = if (data.timestamp in (nowMs - 2000L)..nowMs) data.timestamp else nowMs
        val date = dateFormat.format(Date(tsMs))
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

        w.write(
            String.format(
                Locale.US,
                "%s,%.1f,%.1f,%.1f,%d,%.1f,%.6f,%.6f,%.1f,%.1f,%.1f,%.1f,%.3f,%.3f,%.3f",
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
                data.accelY
            )
        )
        w.newLine()
        rowCount++

        // Flush every 10 rows
        if (rowCount % 10 == 0) {
            w.flush()
        }
    }

    fun close() {
        writer?.flush()
        writer?.close()
        writer = null
    }
}
