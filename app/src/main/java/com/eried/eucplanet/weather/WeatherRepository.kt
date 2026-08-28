package com.eried.eucplanet.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Forecast providers the rider can choose from. Both are keyless: Open-Meteo
 * needs nothing at all, MET Norway asks only for an identifying User-Agent.
 * Adding a provider is one entry here plus its parser in [WeatherRepository];
 * the settings dropdown and the drift guard read this registry.
 */
enum class WeatherSource(
    val id: String,
    val label: String,
    /** Non-null: the national model requested through the Open-Meteo API. */
    val openMeteoModel: String? = null,
) {
    OPEN_METEO("OPEN_METEO", "Open-Meteo"),
    MET_NO("MET_NO", "MET Norway"),
    ECMWF("ECMWF", "ECMWF (via Open-Meteo)", "ecmwf_ifs025"),
    NOAA_GFS("NOAA_GFS", "NOAA GFS (via Open-Meteo)", "gfs_seamless"),
    DWD_ICON("DWD_ICON", "DWD ICON (via Open-Meteo)", "icon_seamless"),
    METEO_FRANCE("METEO_FRANCE", "Météo-France (via Open-Meteo)", "meteofrance_seamless");

    companion object {
        fun byId(id: String): WeatherSource = entries.firstOrNull { it.id == id } ?: OPEN_METEO
    }
}

/** One fetched forecast: a week of hours from [fetchedAtMs], at [lat]/[lon]. */
data class WeatherForecast(
    val hours: List<HourForecast>,
    val fetchedAtMs: Long,
    val lat: Double,
    val lon: Double,
    val source: WeatherSource,
    /** True when these points are 15 minutes apart rather than an hour, so a
     *  window change back to a long view refetches instead of drawing two
     *  days of quarter-hours. */
    val fine: Boolean = false,
)

/**
 * Fetches and caches the hourly forecast for the rider's position.
 *
 * One fetch covers the longest window the UI offers (a week), so switching
 * between "next 6 hours" and "3 days" in the flyout is a slice of the cached
 * list, never another network call. [ensureFresh] is cheap to call whenever
 * the dashboard shows: it refetches only when the cache is older than
 * [FRESH_MS], the rider moved a few kilometres, or the source changed.
 */
@Singleton
class WeatherRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _forecast = MutableStateFlow<WeatherForecast?>(null)
    val forecast: StateFlow<WeatherForecast?> = _forecast.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Last fetch failure, cleared by the next success. The flyout shows it
     *  only when there is no cached forecast to draw instead. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val mutex = Mutex()

    /** Forecast at the navigator's destination, fetched on demand when the
     *  rider flips the flyout's location chip. Same freshness rules. */
    private val _destForecast = MutableStateFlow<WeatherForecast?>(null)
    val destForecast: StateFlow<WeatherForecast?> = _destForecast.asStateFlow()

    suspend fun ensureFresh(
        lat: Double,
        lon: Double,
        source: WeatherSource,
        force: Boolean = false,
        // Short windows ask for 15-minute steps where the provider has them,
        // so an eight-hour look is a curve rather than eight dots.
        fine: Boolean = false,
    ) {
        mutex.withLock {
            val cur = _forecast.value
            val fresh = cur != null &&
                cur.source == source &&
                cur.fine == fine &&
                System.currentTimeMillis() - cur.fetchedAtMs < FRESH_MS &&
                abs(cur.lat - lat) < MOVE_DEG && abs(cur.lon - lon) < MOVE_DEG
            if (fresh && !force) return
            _refreshing.value = true
            try {
                val hours = withContext(Dispatchers.IO) {
                    if (source == WeatherSource.MET_NO) fetchMetNo(lat, lon)
                    else fetchOpenMeteo(lat, lon, source.openMeteoModel, fine)
                }
                _forecast.value = WeatherForecast(
                    hours, System.currentTimeMillis(), lat, lon, source,
                    // MET Norway's own API is hourly whatever we ask for, so
                    // the flag records what we actually got.
                    fine = fine && source != WeatherSource.MET_NO,
                )
                _error.value = null
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Like [ensureFresh], for the destination slot. */
    suspend fun ensureFreshDest(
        lat: Double,
        lon: Double,
        source: WeatherSource,
        force: Boolean = false,
        fine: Boolean = false,
    ) {
        mutex.withLock {
            val cur = _destForecast.value
            val fresh = cur != null &&
                cur.source == source &&
                cur.fine == fine &&
                System.currentTimeMillis() - cur.fetchedAtMs < FRESH_MS &&
                abs(cur.lat - lat) < MOVE_DEG && abs(cur.lon - lon) < MOVE_DEG
            if (fresh && !force) return
            _refreshing.value = true
            try {
                val hours = withContext(Dispatchers.IO) {
                    if (source == WeatherSource.MET_NO) fetchMetNo(lat, lon)
                    else fetchOpenMeteo(lat, lon, source.openMeteoModel, fine)
                }
                _destForecast.value = WeatherForecast(
                    hours, System.currentTimeMillis(), lat, lon, source,
                    fine = fine && source != WeatherSource.MET_NO,
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: t.javaClass.simpleName
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IllegalStateException("empty body")
        }
    }

    /** Open-Meteo: unix timestamps, metric fields, up to 8 days hourly.
     *  [model] picks a national model (ECMWF, GFS, ICON, Météo-France);
     *  null lets Open-Meteo blend its best match. */
    private fun fetchOpenMeteo(
        lat: Double,
        lon: Double,
        model: String? = null,
        fine: Boolean = false,
    ): List<HourForecast> {
        // minutely_15 carries every field the hourly block does, for every
        // model Open-Meteo serves, out to about two days. Only asked for
        // when the window is short enough to show the difference.
        val block = if (fine) "minutely_15" else "hourly"
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=%.4f&longitude=%.4f".format(Locale.US, lat, lon) +
            "&$block=temperature_2m,precipitation,snowfall,wind_speed_10m,is_day" +
            ",relative_humidity_2m,wind_gusts_10m" +
            "&wind_speed_unit=ms&timeformat=unixtime&forecast_days=8" +
            (model?.let { "&models=$it" } ?: "")
        return parseOpenMeteo(get(url), block).map {
            it.copy(goldenWeight = SunCalc.goldenWeight(it.timeMs, lat, lon))
        }
    }

    fun parseOpenMeteo(body: String, block: String = "hourly"): List<HourForecast> {
        val hourly = JSONObject(body).getJSONObject(block)
        val time = hourly.getJSONArray("time")
        val temp = hourly.getJSONArray("temperature_2m")
        val precip = hourly.getJSONArray("precipitation")
        val snow = hourly.getJSONArray("snowfall")
        val wind = hourly.getJSONArray("wind_speed_10m")
        val isDay = hourly.getJSONArray("is_day")
        // Optional in older canned payloads; the detail charts read them.
        val hum = hourly.optJSONArray("relative_humidity_2m")
        val gust = hourly.optJSONArray("wind_gusts_10m")
        return (0 until time.length()).mapNotNull { i ->
            // National models publish fewer hourly days than the 8 requested
            // (Météo-France ~4, ICON ~7.5) and pad the tail with nulls. Skip
            // those rows instead of fabricating calm 0-degree hours.
            if (temp.isNull(i)) return@mapNotNull null
            // Snowfall arrives in cm and is also counted inside "precipitation"
            // as melted water; subtract so rain is liquid rain alone.
            val snowCm = snow.optDouble(i, 0.0).toFloat()
            val rainMm = (precip.optDouble(i, 0.0) - snow.optDouble(i, 0.0) * 0.7)
                .coerceAtLeast(0.0).toFloat()
            HourForecast(
                timeMs = time.getLong(i) * 1000L,
                tempC = temp.optDouble(i, 0.0).toFloat(),
                precipMmH = rainMm,
                snowCmH = snowCm,
                windMs = wind.optDouble(i, 0.0).toFloat(),
                isDay = isDay.optInt(i, 1) == 1,
                humidityPct = hum?.optDouble(i, 0.0)?.toFloat() ?: 0f,
                gustMs = (gust?.optDouble(i, 0.0)?.toFloat() ?: 0f)
                    .coerceAtLeast(wind.optDouble(i, 0.0).toFloat()),
            )
        }
    }

    /** MET Norway compact: ISO times, hourly near-term. Snow and night are
     *  read from the symbol code, which is how MET itself labels them. */
    private fun fetchMetNo(lat: Double, lon: Double): List<HourForecast> {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
            "?lat=%.4f&lon=%.4f".format(Locale.US, lat, lon)
        return parseMetNo(get(url)).map {
            it.copy(goldenWeight = SunCalc.goldenWeight(it.timeMs, lat, lon))
        }
    }

    fun parseMetNo(body: String): List<HourForecast> {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val series = JSONObject(body).getJSONObject("properties").getJSONArray("timeseries")
        val out = ArrayList<HourForecast>()
        for (i in 0 until series.length()) {
            val entry = series.getJSONObject(i)
            val data = entry.getJSONObject("data")
            // Only the entries that carry an hourly block; the 6-hourly tail
            // is coarser than the score wants to pretend to be.
            val next1 = data.optJSONObject("next_1_hours") ?: continue
            val details = data.getJSONObject("instant").getJSONObject("details")
            val symbol = next1.optJSONObject("summary")?.optString("symbol_code").orEmpty()
            val amount = next1.optJSONObject("details")
                ?.optDouble("precipitation_amount", 0.0) ?: 0.0
            val isSnow = symbol.contains("snow") || symbol.contains("sleet")
            out.add(
                HourForecast(
                    timeMs = iso.parse(entry.getString("time"))?.time ?: continue,
                    tempC = details.optDouble("air_temperature", 0.0).toFloat(),
                    precipMmH = if (isSnow) 0f else amount.toFloat(),
                    // mm of water is roughly a centimetre of snow.
                    snowCmH = if (isSnow) amount.toFloat() else 0f,
                    windMs = details.optDouble("wind_speed", 0.0).toFloat(),
                    isDay = !symbol.contains("_night"),
                    humidityPct = details.optDouble("relative_humidity", 0.0).toFloat(),
                    gustMs = details.optDouble("wind_speed_of_gust", 0.0).toFloat()
                        .coerceAtLeast(details.optDouble("wind_speed", 0.0).toFloat()),
                )
            )
        }
        return out
    }

    companion object {
        private const val FRESH_MS = 30 * 60_000L
        /** ~0.05 degrees is a few kilometres: far enough that the forecast
         *  cell genuinely changes. */
        private const val MOVE_DEG = 0.05
        private const val USER_AGENT = "EUCPlanet (github.com/eried/eucplanet)"
    }
}
