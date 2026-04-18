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
 * Format: Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed
 * The trailing GPS speed column is an EUC Planet extension; DarknessBot viewers ignore trailing columns.
 */
class CsvWriter(private val file: File) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var rowCount = 0

    fun open() {
        writer = BufferedWriter(FileWriter(file))
        writer?.write("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage,GPS speed")
        writer?.newLine()
    }

    fun writeRow(data: WheelData, location: Location?) {
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

        // Prefer wheel speed; fall back to GPS speed (m/s -> km/h) when the wheel is silent.
        val gpsSpeedKmh = if (location?.hasSpeed() == true) location.speed * 3.6f else 0f
        val speed = if (data.speed != 0f) data.speed else gpsSpeedKmh

        w.write(
            "$date,%.1f,%.1f,%.1f,%d,%.1f,%.6f,%.6f,%.1f,%.1f".format(
                speed,
                data.voltage,
                data.maxTemperature,
                data.batteryPercent,
                alt,
                lat,
                lon,
                data.totalDistance,
                gpsSpeedKmh
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
