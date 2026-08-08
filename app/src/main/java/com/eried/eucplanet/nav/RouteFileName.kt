package com.eried.eucplanet.nav

import java.util.Locale

/**
 * Builds a filesystem-safe base name (no extension) for a saved route GPX.
 * Destinations are lowercased, spaces and punctuation within a name become
 * "_", the first and last stop are joined by "-", and the name ends in
 * "-route" (the caller appends ".gpx"). Unnamed stops yield just "route".
 * e.g. ["Storgata", "Tromsdalen Center"] -> "storgata-tromsdalen_center-route".
 */
object RouteFileName {

    fun suggest(stopNames: List<String>): String {
        val slugs = stopNames.map { slug(it) }.filter { it.isNotEmpty() }
        val dests = when {
            slugs.size >= 2 -> "${slugs.first()}-${slugs.last()}"
            slugs.size == 1 -> slugs.first()
            else -> ""
        }
        return if (dests.isEmpty()) "route" else "$dests-route"
    }

    /** Lowercase ASCII slug: any run of non-alphanumeric characters collapses to
     *  a single "_", so spaces, punctuation, and dropped accents never leak an
     *  unsafe or ambiguous character into the "-" joined filename (a slug never
     *  contains "-", so the joiner stays unambiguous). */
    private fun slug(raw: String): String =
        raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
}
