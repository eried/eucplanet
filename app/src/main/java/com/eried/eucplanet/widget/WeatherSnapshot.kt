package com.eried.eucplanet.widget

import android.content.Context
import androidx.core.content.edit

/**
 * The last weather verdict, on disk, for the launcher to paint.
 *
 * The forecast repository keeps its hours in memory only, and a widget is
 * inflated by a broadcast the app process may not even be alive for. So the
 * numbers a widget can draw are exactly the ones written here, and a widget
 * that has never been refreshed says so rather than drawing an empty graph.
 *
 * SharedPreferences because a receiver reads synchronously, matching
 * [EucWidget.Snapshot]. The series is packed into one string rather than a
 * JSON blob: it is written on every refresh and read on every inflate.
 */
data class WeatherSnapshot(
    /** When the forecast behind this was fetched. 0 = nothing ever fetched. */
    val fetchedAtMs: Long = 0L,
    /** Score of the hour happening now, -5..5 ish. */
    val score: Float = 0f,
    /** [com.eried.eucplanet.weather.WeatherFace] key for that hour. */
    val faceKey: String = "MEH",
    /** Chip-sized place name, blank when the geocoder gave nothing. */
    val place: String = "",
    /** Temperature and wind for the current hour, already in the rider's units
     *  and formatted, because the widget has no unit preferences to hand. */
    val tempLabel: String = "",
    val windLabel: String = "",
    /** The window the rider configured, hours. Drawn as the graph's span. */
    val windowHours: Int = 8,
    /** Scores across that window, evenly spaced from [seriesStartMs] every
     *  [seriesStepMs]. Empty until the first successful refresh. */
    val series: List<Float> = emptyList(),
    val seriesStartMs: Long = 0L,
    val seriesStepMs: Long = 0L,
    /** Face key per series point, for the strip along the graph. Same length
     *  as [series] when present. */
    val faces: List<String> = emptyList(),
    /** Set when the last refresh failed and there is nothing cached to show. */
    val failed: Boolean = false,
    /** Where this forecast was for. Kept so a background refresh has somewhere
     *  to ask about when the system has no last-known fix to offer, which is
     *  the normal case on a phone with location off. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
) {
    val hasData: Boolean get() = fetchedAtMs > 0L && series.isNotEmpty()

    companion object {
        private const val PREFS = "weather_widget"
        /** Unit separator: it cannot appear in a formatted number or a
         *  face key, so the packing needs no escaping. Written as an
         *  escape rather than the raw byte, which is invisible in a diff. */
        private const val SEP = "\u001F"

        fun load(context: Context): WeatherSnapshot {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WeatherSnapshot(
                fetchedAtMs = p.getLong("fetched", 0L),
                score = p.getFloat("score", 0f),
                faceKey = p.getString("face", "MEH") ?: "MEH",
                place = p.getString("place", "") ?: "",
                tempLabel = p.getString("temp", "") ?: "",
                windLabel = p.getString("wind", "") ?: "",
                windowHours = p.getInt("window", 8),
                series = unpackFloats(p.getString("series", "") ?: ""),
                seriesStartMs = p.getLong("series_start", 0L),
                seriesStepMs = p.getLong("series_step", 0L),
                faces = unpackStrings(p.getString("faces", "") ?: ""),
                failed = p.getBoolean("failed", false),
                // Doubles are not a SharedPreferences type; the bits are.
                lat = Double.fromBits(p.getLong("lat", 0L)),
                lon = Double.fromBits(p.getLong("lon", 0L)),
            )
        }

        fun save(context: Context, s: WeatherSnapshot) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putLong("fetched", s.fetchedAtMs)
                putFloat("score", s.score)
                putString("face", s.faceKey)
                putString("place", s.place)
                putString("temp", s.tempLabel)
                putString("wind", s.windLabel)
                putInt("window", s.windowHours)
                putString("series", packFloats(s.series))
                putLong("series_start", s.seriesStartMs)
                putLong("series_step", s.seriesStepMs)
                putString("faces", s.faces.joinToString(SEP))
                putBoolean("failed", s.failed)
                putLong("lat", s.lat.toRawBits())
                putLong("lon", s.lon.toRawBits())
            }
        }

        /** One decimal is all a 0..10 point graph can show, and it keeps a
         *  week-long series inside a few hundred bytes. */
        fun packFloats(v: List<Float>): String =
            v.joinToString(SEP) { "%.1f".format(java.util.Locale.US, it) }

        fun unpackFloats(s: String): List<Float> =
            if (s.isBlank()) emptyList()
            else s.split(SEP).mapNotNull { it.toFloatOrNull() }

        fun unpackStrings(s: String): List<String> =
            if (s.isBlank()) emptyList() else s.split(SEP)
    }
}
