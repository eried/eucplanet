package com.eried.eucplanet.data.repository

import com.eried.eucplanet.util.TripCsv
import java.io.File

/**
 * Building new trip files out of existing ones: a section of one ride, or
 * several rides joined together.
 *
 * ## Why "derived" is encoded in the file name
 *
 * A derived trip must never reach the eucstats leaderboard: it is a view the
 * rider constructed, not a ride as recorded. Upload eligibility is a database
 * query that needs a non-null `tripUuid`, so leaving that null is enough day to
 * day. It is not enough after a database rebuild: TripRepository adopts any
 * orphan CSV it finds and hands it a fresh UUID, which would quietly make these
 * uploadable. The file name is the only thing that survives that, so it carries
 * the marker.
 *
 * Backups need no special handling in the other direction. Dropbox and local
 * sync walk the trips directory for `*.csv`, so a derived file is included
 * automatically, which is what the rider wants.
 *
 * Rows are copied verbatim from the source CSV rather than re-serialised from
 * parsed points. A trip file can carry columns the app does not model, and a
 * round trip through [com.eried.eucplanet.ui.recording.TripDataPoint] would
 * silently drop them.
 */
object TripDerive {

    /** Marks a file as built by the app rather than recorded. */
    const val DERIVED_MARKER = "_derived"

    fun isDerived(fileName: String): Boolean = fileName.contains(DERIVED_MARKER)

    /** A free file name next to [source], carrying the derived marker. */
    fun sectionFileName(sourceName: String, stamp: String): String {
        val base = sourceName.removeSuffix(".csv").removeSuffix(".CSV")
        return "$base$DERIVED_MARKER$stamp.csv"
    }

    /**
     * Copy the header plus every data row whose timestamp falls within
     * [startMs]..[endMs] into [dest].
     *
     * Selection is by timestamp, not row index: [readTripData] skips malformed
     * lines, so an index taken from the parsed list would not line up with the
     * file once a single row is short of columns.
     *
     * @return rows written, excluding the header. 0 means nothing matched and
     *   the caller should discard [dest].
     */
    fun writeSection(source: File, dest: File, startMs: Long, endMs: Long): Int {
        if (!source.exists()) return 0
        var written = 0
        source.bufferedReader().use { reader ->
            val header = reader.readLine() ?: return 0
            dest.bufferedWriter().use { out ->
                out.write(header)
                out.newLine()
                // One parser for the file, resolved from the first row that
                // yields one. See TripCsv.parserFor for why probing per row is
                // expensive.
                var parse: ((String?) -> Long?)? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    val dateCell = line.substringBefore(',')
                    if (parse == null) parse = TripCsv.parserFor(dateCell)
                    val t = parse?.invoke(dateCell) ?: continue
                    if (t in startMs..endMs) {
                        out.write(line)
                        out.newLine()
                        written++
                    }
                }
            }
        }
        return written
    }

    /**
     * Rewrite the wheel identity recorded in [source]'s Extra column into [dest].
     *
     * The Extra column is what eucviewer, a shared file and the trip map read,
     * so correcting only the database row would leave the file disagreeing with
     * the app. Rows carrying `wheel.name=` or `wheel.mac=` get the new value;
     * everything else is copied byte for byte.
     *
     * @return rows rewritten. 0 means the file records no wheel identity at all,
     *   which is normal for an imported foreign CSV.
     */
    fun rewriteWheelIdentity(source: File, dest: File, name: String, mac: String?): Int {
        if (!source.exists()) return 0
        var changed = 0
        source.bufferedReader().use { reader ->
            val headerLine = reader.readLine() ?: return 0
            val extraIdx = headerLine.lowercase().split(",").map { it.trim() }.indexOf("extra")
            dest.bufferedWriter().use { out ->
                out.write(headerLine)
                out.newLine()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (extraIdx < 0) { out.write(line); out.newLine(); continue }
                    val cells = line.split(",")
                    val cell = cells.getOrNull(extraIdx)?.trim().orEmpty()
                    val replacement = when {
                        cell.startsWith("wheel.name=") -> "wheel.name=$name"
                        cell.startsWith("wheel.mac=") && mac != null ->
                            "wheel.mac=" + mac.replace(":", "").replace("-", "").uppercase()
                        else -> null
                    }
                    if (replacement == null) { out.write(line); out.newLine(); continue }
                    val rebuilt = cells.toMutableList()
                    rebuilt[extraIdx] = replacement
                    out.write(rebuilt.joinToString(","))
                    out.newLine()
                    changed++
                }
            }
        }
        return changed
    }

    /**
     * Upsert a rider-set trip name into the CSV Extra column as a single
     * `trip.name=<name>` pair, so a rename survives export and a Dropbox
     * round-trip with no sidecar file (the same convention eucviewer reads).
     *
     * A blank [name] removes the pair (the reader then falls back to the date).
     * The name is sanitised like any Extra cell (commas / quotes / newlines
     * collapse to a space) and capped at 60 chars to match the reader.
     *
     * Placement: the first existing `trip.name=` cell is rewritten (and any
     * further duplicates cleared) so the reader, which takes the first hit,
     * always sees the new value. If none exists, the pair is dropped into the
     * first row whose Extra cell is empty - the same place wheel.* pairs ride,
     * a normal telemetry row, never a fabricated one.
     *
     * @return 1 if the name was written/updated/removed, 0 if the file has no
     *   Extra column (a foreign import) or no slot was available.
     */
    fun rewriteTripName(source: File, dest: File, name: String): Int {
        if (!source.exists()) return 0
        // Collapse commas / quotes / any whitespace run to a single space so the
        // name can't break CSV framing and reads cleanly. Capped to match reader.
        val clean = name.replace(Regex("[,\"\\s]+"), " ").trim().take(60)
        // Pass 1: is there already a trip.name= row to overwrite in place?
        var hasExisting = false
        source.bufferedReader().use { reader ->
            val header = reader.readLine() ?: return 0
            val idx = header.lowercase().split(",").map { it.trim() }.indexOf("extra")
            if (idx < 0) return 0
            while (true) {
                val line = reader.readLine() ?: break
                val cell = line.split(",").getOrNull(idx)?.trim().orEmpty()
                if (cell.startsWith("trip.name=", ignoreCase = true)) { hasExisting = true; break }
            }
        }
        var wrote = 0
        var placed = false
        source.bufferedReader().use { reader ->
            val header = reader.readLine() ?: return 0
            val idx = header.lowercase().split(",").map { it.trim() }.indexOf("extra")
            dest.bufferedWriter().use { out ->
                out.write(header); out.newLine()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (idx < 0) { out.write(line); out.newLine(); continue }
                    val cells = line.split(",").toMutableList()
                    val cell = cells.getOrNull(idx)?.trim().orEmpty()
                    val newCell: String? = when {
                        cell.startsWith("trip.name=", ignoreCase = true) ->
                            if (!placed && clean.isNotEmpty()) { placed = true; "trip.name=$clean" }
                            else "" // clear duplicates, or blank the name entirely
                        !placed && !hasExisting && clean.isNotEmpty() && cell.isEmpty() &&
                            idx < cells.size -> { placed = true; "trip.name=$clean" }
                        else -> null
                    }
                    if (newCell == null) { out.write(line); out.newLine(); continue }
                    cells[idx] = newCell
                    out.write(cells.joinToString(","))
                    out.newLine()
                    wrote = 1
                }
            }
        }
        return wrote
    }

    /**
     * Concatenate [sources] into [dest], oldest first, keeping one header.
     *
     * Each source's header is read and discarded rather than assumed identical:
     * trips recorded across app versions can carry different column sets. The
     * FIRST file's header wins, so a later file with extra columns loses them
     * rather than producing rows that do not match the header. Callers should
     * therefore order sources deliberately.
     *
     * @return rows written, excluding the header.
     */
    fun writeJoined(sources: List<File>, dest: File): Int {
        val present = sources.filter { it.exists() }
        if (present.isEmpty()) return 0
        var written = 0
        dest.bufferedWriter().use { out ->
            var headerWritten = false
            for (file in present) {
                file.bufferedReader().use { reader ->
                    val header = reader.readLine() ?: return@use
                    if (!headerWritten) {
                        out.write(header)
                        out.newLine()
                        headerWritten = true
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        out.write(line)
                        out.newLine()
                        written++
                    }
                }
            }
        }
        return written
    }
}
