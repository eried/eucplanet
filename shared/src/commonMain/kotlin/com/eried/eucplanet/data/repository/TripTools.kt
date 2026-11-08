package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.platform.formatLocalDateUs
import com.eried.eucplanet.util.TripCsv

/**
 * The trip tools, the same on both phones: save a section, split, combine,
 * rename and change wheel. Each writes the trip CSVs ([TripDerive]) and the
 * trip rows the same way, so a trip cut on the iPhone is the file an Android
 * phone would have written, and Dropbox sees the same names.
 *
 * Android's TripRepository and the iPhone's IosTrips hand it their trips
 * folder ([files]), the wheel profile names and [resync], which pushes an
 * edited or new trip to the rider's backups.
 */
class TripTools(
    private val tripDao: TripDao,
    private val files: TripToolFiles,
    private val profileNames: suspend () -> List<String>,
    private val resync: suspend (tripId: Long) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private companion object {
        const val TAG = "TripRepo"
    }

    private fun stamp(): String = formatLocalDateUs("yyyyMMdd_HHmmss", now())

    /**
     * Write a section of [source] out as a new trip and return its row.
     *
     * Non-destructive: [source] is untouched, so a misjudged range costs the
     * rider nothing but a spare entry they can delete.
     *
     * The new trip gets no `tripUuid`, which is what keeps it off the eucstats
     * leaderboard: upload eligibility is a query requiring a non-null uuid. It
     * still lands in the trips directory, so Dropbox and local backup pick it up
     * like any other file.
     *
     * @param startMs absolute epoch millis, inclusive
     * @param endMs absolute epoch millis, inclusive
     * @return the new trip, or null when the range held no rows
     */
    suspend fun saveTripSection(source: TripRecord, startMs: Long, endMs: Long): TripRecord? {
        if (!files.exists(source.fileName)) return null
        return saveSectionInternal(source, startMs, endMs, index = 0)
    }

    /** Shared by [saveTripSection] and [splitTrip]. [index] > 0 numbers the
     *  piece so a split does not produce several identically named files. */
    private suspend fun saveSectionInternal(
        source: TripRecord,
        startMs: Long,
        endMs: Long,
        index: Int,
    ): TripRecord? {
        val stamp = stamp() + if (index > 0) "_$index" else ""
        val destName = TripDerive.sectionFileName(source.fileName, stamp)
        val rows = runCatching {
            files.write(destName) { TripDerive.writeSection(files.lines(source.fileName), it, startMs, endMs) }
        }.getOrElse { e ->
            Log.e(TAG, "Could not write trip section", e)
            runCatching { files.delete(destName) }
            return null
        }
        if (rows <= 0) {
            runCatching { files.delete(destName) }
            return null
        }
        stampDerived(destName, source.wheelMetaJson)
        val metrics = TripCsv.metricsFrom(readQuads(destName))
        val record = TripRecord(
            fileName = destName,
            startTime = if (metrics.valid) metrics.startMs else startMs,
            endTime = if (metrics.valid) metrics.endMs else endMs,
            distanceKm = metrics.distanceKm,
            sampleCount = rows,
            // Deliberately null. See the KDoc above and TripDerive.
            tripUuid = null,
            wheelMetaJson = source.wheelMetaJson,
        )
        val id = tripDao.insert(record)
        return record.copy(id = id)
    }

    /** The wheel the trip row knows, written into a derived file; see
     *  [TripDerive.stampDerived]. Best effort: a file that cannot carry it
     *  is still a valid trip. */
    private fun stampDerived(name: String, wheelJson: String?) {
        val identity = WheelChoice.fromJson(wheelJson)?.extraFields().orEmpty()
        runCatching { TripDerive.stampDerived(files, name, identity) }
            .onFailure { Log.w(TAG, "Could not stamp derived $name", it) }
    }

    /** Date, lat, lon and odometer per row, for metric recomputation. */
    private fun readQuads(name: String): List<TripCsv.Quad> {
        val out = ArrayList<TripCsv.Quad>()
        files.lines(name).useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext()) return@useLines
            val header = it.next().lowercase().split(",").map { h -> h.trim() }
            val iLat = TripCsv.Columns.latitude(header)
            val iLon = TripCsv.Columns.longitude(header)
            val iMil = TripCsv.Columns.mileage(header)
            while (it.hasNext()) {
                val p = it.next().split(",")
                out.add(
                    TripCsv.Quad(
                        date = p.getOrNull(0).orEmpty(),
                        lat = p.getOrNull(iLat)?.toDoubleOrNull() ?: 0.0,
                        lon = p.getOrNull(iLon)?.toDoubleOrNull() ?: 0.0,
                        mileage = p.getOrNull(iMil)?.toFloatOrNull() ?: 0f,
                    )
                )
            }
        }
        return out
    }

    /**
     * Read the wheel out of every finished trip file that has never been
     * asked. Old rows predate the wheelMetaJson column and restored trips
     * arrived before downloads captured it, so the change-wheel picker sat
     * empty while every CSV named its wheel. One streaming read per file,
     * once ever.
     */
    suspend fun backfillWheelMetaFromFiles() {
        for (trip in tripDao.tripsWithoutWheelMeta()) {
            if (!files.exists(trip.fileName)) continue
            val json = runCatching { readWheelJsonFromCsv(trip.fileName) }.getOrNull()
            // "{}" = looked, nothing there. Distinguishable from NULL (never
            // looked) so the sweep converges instead of re-reading forever.
            tripDao.updateWheelMeta(trip.id, json ?: "{}")
        }
    }

    /** First wheel.name= / wheel.mac= / brand / model in the Extra column,
     *  as the same JSON shape the recorder caches. Streams where the
     *  platform can; a long ride never sits in memory on Android. */
    private fun readWheelJsonFromCsv(fileName: String): String? {
        var name: String? = null; var mac: String? = null
        var brand: String? = null; var model: String? = null
        val carries = files.lines(fileName).useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext()) return@useLines false
            val idx = it.next().lowercase().split(",").map { h -> h.trim() }.indexOf("extra")
            if (idx < 0) return@useLines false
            while (it.hasNext()) {
                val cell = it.next().split(",").getOrNull(idx)?.trim().orEmpty()
                if (cell.startsWith("wheel.", ignoreCase = true)) {
                    val v = cell.substringAfter('=').trim().take(80)
                    if (v.isNotEmpty()) when {
                        cell.startsWith("wheel.name=", true) -> name = name ?: v
                        cell.startsWith("wheel.mac=", true) -> mac = mac ?: v
                        cell.startsWith("wheel.brand=", true) -> brand = brand ?: v
                        cell.startsWith("wheel.model=", true) -> model = model ?: v
                    }
                }
            }
            true
        }
        if (!carries) return null
        if (name == null && mac == null) return null
        return JSONObject().apply {
            name?.let { put("ble_name", it) }
            mac?.let { put("ble_mac", it) }
            brand?.let { put("brand", it) }
            model?.let { put("model", it) }
        }.toString()
    }

    /**
     * Every wheel name worth offering in the trip-tools picker.
     *
     * Two sources, because neither is complete on its own. Saved profiles cover
     * wheels this phone has paired with; the trips themselves cover wheels that
     * only ever arrived as an imported CSV, which is exactly the case where the
     * rider is most likely to be correcting a wrong label.
     */
    suspend fun knownWheels(): List<WheelChoice> {
        val fromProfiles = runCatching { profileNames() }.getOrDefault(emptyList())
            .map { WheelChoice(name = it) }
        val fromTrips = runCatching {
            tripDao.allWheelMeta().mapNotNull { WheelChoice.fromJson(it) }
        }.getOrDefault(emptyList())
        // Distinct the way eucviewer groups: label plus serial or MAC. A
        // profile that only knows a name folds into a trip identity with the
        // same label once one exists, so the list does not show a wheel twice.
        val byKey = LinkedHashMap<String, WheelChoice>()
        for (w in fromTrips + fromProfiles) {
            if (w.isEmpty) continue
            if (byKey.values.any { it.label == w.label && (w.mac == null || it.mac == w.mac) }) continue
            if (w.key !in byKey) byKey[w.key] = w
        }
        return byKey.values.sortedBy { it.label.lowercase() }
    }

    /**
     * Reassign which wheel a trip was ridden on.
     *
     * Rewrites BOTH the database row and the CSV's Extra column, so the app,
     * a shared file and eucviewer agree. The original CSV is kept alongside as
     * `.bak` until the rewrite succeeds, then replaced atomically-ish, because a
     * half-written trip file would be unrecoverable.
     *
     * This never touches an existing leaderboard entry: the caller warns the
     * rider when [TripRecord.eucstatsStatus] says the ride is already up there.
     *
     * @return true when the row was updated
     */
    suspend fun changeTripWheel(trip: TripRecord, wheel: WheelChoice): Boolean {
        rewriteInPlace(trip.fileName, "Wheel rewrite failed for", "Could not swap in the rewritten") { src, out ->
            TripDerive.rewriteWheelIdentity(src, out, wheel.extraFields())
        }
        // The cache is replaced, not merged: merging kept the old wheel's
        // brand and model under the new name, the same stale identity the
        // file rewrite above now clears.
        tripDao.updateWheelMeta(trip.id, wheel.toJson())
        resync(trip.id)
        return true
    }

    /**
     * Give a trip a rider-set name (blank clears it, falling back to the date).
     *
     * The name is written into the CSV Extra column as `trip.name=` so it
     * survives export and a Dropbox round-trip with no sidecar file, and cached
     * on the DB row for a fast list / title. Same atomic .rewrite -> .bak swap
     * as [changeTripWheel] so a mid-write kill can never leave the rider with
     * neither file. Re-syncs afterwards.
     */
    suspend fun renameTrip(trip: TripRecord, name: String): Boolean {
        val clean = name.trim().take(60)
        rewriteInPlace(trip.fileName, "Trip name rewrite failed for", "Could not swap in the renamed") { src, out ->
            TripDerive.rewriteTripName(src, out, clean)
        }
        tripDao.updateCustomName(trip.id, clean.ifBlank { null })
        resync(trip.id)
        return true
    }

    /** Rewrites [fileName] through [rewrite] into a .rewrite file, then swaps it in by way of a .bak. */
    private fun rewriteInPlace(
        fileName: String,
        failedLog: String,
        swapLog: String,
        rewrite: (LineSource, LineSink) -> Int,
    ) {
        if (!files.exists(fileName)) return
        val tmp = "$fileName.rewrite"
        val ok = runCatching { files.write(tmp) { rewrite(files.lines(fileName), it) } >= 0 }
            .getOrElse { e ->
                Log.e(TAG, "$failedLog $fileName", e)
                runCatching { files.delete(tmp) }
                false
            }
        if (ok && files.exists(tmp)) {
            val bak = "$fileName.bak"
            runCatching { files.delete(bak) }
            if (files.rename(fileName, bak) && files.rename(tmp, fileName)) {
                runCatching { files.delete(bak) }
            } else {
                // Put things back rather than leaving the rider with neither.
                runCatching { if (!files.exists(fileName)) files.rename(bak, fileName) }
                runCatching { files.delete(tmp) }
                Log.e(TAG, "$swapLog $fileName")
            }
        }
    }

    /**
     * Cut [source] into pieces at [boundariesMs] (absolute epoch millis, each
     * the first sample of a new piece) and save each as its own trip.
     *
     * Non-destructive: [source] is untouched. Pieces carry no `tripUuid`, so
     * they are backed up but never reach the leaderboard.
     *
     * @return the pieces created, empty when nothing could be written
     */
    suspend fun splitTrip(source: TripRecord, boundariesMs: List<Long>): List<TripRecord> {
        if (boundariesMs.isEmpty()) return emptyList()
        if (!files.exists(source.fileName)) return emptyList()
        // Turn the cut points into inclusive ranges covering the whole ride.
        val edges = (listOf(Long.MIN_VALUE) + boundariesMs.sorted()) + listOf(Long.MAX_VALUE)
        val out = ArrayList<TripRecord>()
        for (i in 0 until edges.size - 1) {
            val from = edges[i]
            val to = if (edges[i + 1] == Long.MAX_VALUE) Long.MAX_VALUE else edges[i + 1] - 1
            val piece = saveSectionInternal(source, from, to, index = i + 1)
            if (piece != null) out.add(piece)
        }
        // A piece is a new file in the trips folder, which the folder worker
        // and the Dropbox sync would both pick up eventually - but only at
        // their next run. A rename pushes straight away, and a rider who just
        // cut a ride in three has the same expectation.
        out.forEach { resync(it.id) }
        return out
    }

    /**
     * Merge [sources] into one new trip, oldest first.
     *
     * Non-destructive, and the result carries no `tripUuid` for the same reason
     * a split piece does not: it is a trip the rider assembled, not one the app
     * recorded.
     */
    suspend fun combineTrips(sources: List<TripRecord>): TripRecord? {
        val ordered = sources.sortedBy { it.startTime }
        val present = ordered.map { it.fileName }.filter { files.exists(it) }
        if (present.size < 2) return null
        val destName = TripDerive.sectionFileName(ordered.first().fileName, stamp() + "_join")
        val rows = runCatching {
            files.write(destName) { sink -> TripDerive.writeJoined(present.map { files.lines(it) }, sink) }
        }.getOrElse { e ->
            Log.e(TAG, "Could not join trips", e)
            runCatching { files.delete(destName) }
            return null
        }
        if (rows <= 0) { runCatching { files.delete(destName) }; return null }
        stampDerived(destName, ordered.first().wheelMetaJson)
        val metrics = TripCsv.metricsFrom(readQuads(destName))
        val record = TripRecord(
            fileName = destName,
            startTime = if (metrics.valid) metrics.startMs else ordered.first().startTime,
            endTime = if (metrics.valid) metrics.endMs else ordered.last().endTime,
            distanceKm = metrics.distanceKm,
            sampleCount = rows,
            tripUuid = null,
            wheelMetaJson = ordered.first().wheelMetaJson,
        )
        return record.copy(id = tripDao.insert(record)).also { resync(it.id) }
    }
}
