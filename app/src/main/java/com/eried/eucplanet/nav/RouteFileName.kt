package com.eried.eucplanet.nav

/**
 * Builds a filesystem-safe base name (no extension) for a saved route GPX.
 * Two or more named stops read "first to last"; one named stop uses its name;
 * anything blank falls back to [fallback] (the route's own name). Sanitised to
 * the same path-safe set the app uses for backup and theme files.
 */
object RouteFileName {

    fun suggest(stopNames: List<String>, fallback: String): String {
        val names = stopNames.map { it.trim() }
        val first = names.firstOrNull().orEmpty()
        val last = names.lastOrNull().orEmpty()
        val raw = when {
            names.size >= 2 && first.isNotBlank() && last.isNotBlank() -> "$first to $last"
            names.size == 1 && first.isNotBlank() -> first
            else -> fallback
        }
        return sanitize(raw).ifBlank { sanitize(fallback).ifBlank { "route" } }
    }

    private fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
}
