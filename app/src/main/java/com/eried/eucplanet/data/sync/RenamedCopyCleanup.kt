package com.eried.eucplanet.data.sync

/**
 * Picks the "trip (N).csv" copies in the backup folder that can go.
 *
 * Shared storage renames a clashing file instead of refusing it, so a backup
 * pass that wrote a trip the folder already had (by a name differing only in
 * case) left "trip (1).csv", "trip (2).csv"... beside the original, one per
 * pass, up to 32. A copy is removed only when its bytes match the original or
 * a copy already kept, so nothing that exists once is ever deleted.
 */
object RenamedCopyCleanup {

    data class Entry(val name: String, val size: Long)

    private val COPY = Regex("""^(.*) \((\d+)\)\.csv$""", RegexOption.IGNORE_CASE)

    /** The names to delete. [sameBytes] is asked only for entries of equal size. */
    fun plan(entries: List<Entry>, sameBytes: (Entry, Entry) -> Boolean): List<String> {
        val byLower = entries.associateBy { it.name.lowercase() }
        val copiesByOriginal = entries.mapNotNull { e ->
            COPY.find(e.name)?.let { m -> Triple("${m.groupValues[1]}.csv".lowercase(), m.groupValues[2].toInt(), e) }
        }.groupBy({ it.first }, { it.second to it.third })

        val remove = mutableListOf<String>()
        for ((originalLower, copies) in copiesByOriginal) {
            val kept = mutableListOf<Entry>()
            byLower[originalLower]?.let { kept += it }
            for ((_, copy) in copies.sortedBy { it.first }) {
                if (kept.any { it.size == copy.size && sameBytes(it, copy) }) remove += copy.name
                else kept += copy
            }
        }
        return remove
    }
}
