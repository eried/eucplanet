package com.eried.eucplanet.data.sync

import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.util.TripCsv

/** What a trip row is built from when a trip arrives as a file (a sync download). */
data class CsvMeta(
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Float,
    /**
     * The rider's name for the trip, carried in the CSV's Extra column as
     * `trip.name=`.
     *
     * A rename writes it into the file precisely so it survives export and
     * Dropbox, but nothing ever read it back: a trip arriving by download
     * was inserted nameless and shown as its date. Rename a trip, let it
     * sync, come back to it on a new phone or after a restore, and the
     * name was gone - the file had it all along.
     */
    val name: String? = null,
    /**
     * The wheel identity the file carries (wheel.name= / wheel.mac= rows
     * in the Extra column), as the same JSON shape TripRecord caches.
     * Same story as the trip name: the file always had it - eucviewer
     * reads exactly these rows - and every download dropped it, so the
     * change-wheel picker on a restored library offered nothing.
     */
    val wheelJson: String? = null,
)

/**
 * Reads a downloaded trip's row out of its CSV. The folder sync and the
 * Dropbox sync use it on Android, the Dropbox sync on iOS, so a trip comes
 * down as the same row on both phones.
 */
object TripCsvMeta {

    fun read(file: TripFile): CsvMeta {
        var startTime = System.currentTimeMillis()
        var endTime = startTime
        var gpsDistanceKm = 0.0
        var tripName: String? = null
        var wheelName: String? = null
        var wheelMac: String? = null
        var wheelBrand: String? = null
        var wheelModel: String? = null
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var minMileage = Float.MAX_VALUE
        var maxMileage = 0f
        var noHeader = false
        try {
            // Stream the CSV line-by-line instead of readText().lines(), so a
            // long trip (a big CSV) never has to sit fully in memory at once.
            file.useLines { lines ->
                val reader = lines.iterator()
                if (!reader.hasNext()) {
                    noHeader = true
                    return@useLines
                }
                val headerLine = reader.next()
                val header = headerLine.lowercase().split(",").map { it.trim() }
                val extraIdx = header.indexOf("extra")
                val dateIdx = TripCsv.Columns.date(header).takeIf { it >= 0 } ?: 0
                val latIdx = TripCsv.Columns.latitude(header).takeIf { it >= 0 } ?: 6
                val lonIdx = TripCsv.Columns.longitude(header).takeIf { it >= 0 } ?: 7
                val mileageIdx = TripCsv.Columns.mileage(header).takeIf { it >= 0 } ?: 8
                var first = true
                // Stay streaming (one big CSV never fully resident), but share
                // the timestamp + great-circle logic with the import/detail
                // paths via TripCsv so every surface agrees on duration/distance.
                reader.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEach
                    val parts = line.split(",")
                    if (parts.size < 2) return@forEach
                    if (extraIdx >= 0) {
                        val cell = parts.getOrNull(extraIdx)?.trim().orEmpty()
                        if (tripName == null && cell.startsWith("trip.name=", ignoreCase = true)) {
                            tripName = cell.substringAfter('=').trim().take(60)
                                .takeIf { it.isNotEmpty() }
                        }
                        if (cell.startsWith("wheel.", ignoreCase = true)) {
                            val v = cell.substringAfter('=').trim().take(80)
                            if (v.isNotEmpty()) when {
                                cell.startsWith("wheel.name=", true) -> wheelName = wheelName ?: v
                                cell.startsWith("wheel.mac=", true) -> wheelMac = wheelMac ?: v
                                cell.startsWith("wheel.brand=", true) -> wheelBrand = wheelBrand ?: v
                                cell.startsWith("wheel.model=", true) -> wheelModel = wheelModel ?: v
                            }
                        }
                    }
                    TripCsv.parseDate(parts.getOrNull(dateIdx)?.trim())?.let { t ->
                        if (first) { startTime = t; first = false }
                        endTime = t
                    }
                    val lat = parts.getOrNull(latIdx)?.toDoubleOrNull()
                    val lon = parts.getOrNull(lonIdx)?.toDoubleOrNull()
                    if (lat != null && lon != null && lat != 0.0 && lon != 0.0) {
                        if (!lastLat.isNaN() && !lastLon.isNaN()) {
                            val d = TripCsv.haversineMeters(lastLat, lastLon, lat, lon)
                            if (d in 0.5..200.0) gpsDistanceKm += d / 1000.0
                        }
                        lastLat = lat
                        lastLon = lon
                    }
                    val mileage = parts.getOrNull(mileageIdx)?.toFloatOrNull()
                    if (mileage != null && mileage > 0f) {
                        if (mileage < minMileage) minMileage = mileage
                        if (mileage > maxMileage) maxMileage = mileage
                    }
                }
            }
        } catch (_: Exception) {}
        if (noHeader) return CsvMeta(startTime, endTime, 0f)
        val distance = when {
            gpsDistanceKm > 0.0 -> gpsDistanceKm.toFloat()
            minMileage != Float.MAX_VALUE && maxMileage > minMileage -> maxMileage - minMileage
            else -> 0f
        }
        val wheelJson = if (wheelName == null && wheelMac == null) null else
            JSONObject().apply {
                wheelName?.let { put("ble_name", it) }
                wheelMac?.let { put("ble_mac", it) }
                wheelBrand?.let { put("brand", it) }
                wheelModel?.let { put("model", it) }
            }.toString()
        return CsvMeta(startTime, endTime, distance, tripName, wheelJson)
    }
}
