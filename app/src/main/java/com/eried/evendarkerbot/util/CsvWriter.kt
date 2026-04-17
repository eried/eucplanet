package com.eried.evendarkerbot.util

import android.location.Location
import com.eried.evendarkerbot.data.model.WheelData
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes DarknessBot-compatible CSV files.
 * Format: Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage
 */
class CsvWriter(private val file: File) {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS", Locale.US)
    private var writer: BufferedWriter? = null
    private var rowCount = 0

    fun open() {
        writer = BufferedWriter(FileWriter(file))
        writer?.write("Date,Speed,Voltage,Temperature,Battery level,Altitude,Latitude,Longitude,Total mileage")
        writer?.newLine()
    }

    fun writeRow(data: WheelData, location: Location?) {
        val w = writer ?: return
        val date = dateFormat.format(Date(data.timestamp))
        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val alt = location?.altitude ?: 0.0

        w.write(
            "$date,%.1f,%.1f,%.1f,%d,%.1f,%.6f,%.6f,%.1f".format(
                data.speed,
                data.voltage,
                data.maxTemperature,
                data.batteryPercent,
                alt,
                lat,
                lon,
                data.totalDistance
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
