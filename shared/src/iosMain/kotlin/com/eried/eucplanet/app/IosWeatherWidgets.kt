package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.AdvancedSettings
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.WeatherSettings
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.dashboard.WeatherPanelLaunch
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.weather.WeatherFace
import com.eried.eucplanet.weather.WeatherForecast
import com.eried.eucplanet.weather.WeatherSource
import com.eried.eucplanet.widget.WeatherWidgetContent
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid

/**
 * The iPhone's weather widgets: Android's "Good to ride?" and "Riding
 * weather", with the scores, faces and labels the panel shows
 * ([WeatherWidgetContent]).
 *
 * The app writes the scored forecast into a file the widget extension reads
 * (the App Group container, beside [IosWidgets]' own) and asks iOS to draw
 * the two widgets again; iosApp/Widgets/WeatherWidgets.swift lays it out.
 *
 * What iOS does differently from Android: no hourly worker. The file carries
 * every forecast step ahead, already scored, so a widget moves along them by
 * itself and shows the hour that is now for as long as the forecast reaches.
 * A new forecast comes when the rider opens the dashboard, when the app
 * leaves the screen, when iOS grants background time ([IosBackgroundSync])
 * and from the widget's refresh button (iOS 17).
 */
object IosWeatherWidgets {
    private const val TAG = "IosWeatherWidgets"
    const val FILE = "weather.json"

    /** The widget's refresh button: a key no quick action has, pressed through RideActionIntent. */
    const val REFRESH_KEY = "WEATHER_REFRESH"

    /** What a tap on a widget opens (WeatherWidgets.swift): the app, on its weather panel. */
    private const val PANEL_URL = "eucplanet://weather"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writing = Mutex()
    private var lastKey: Key? = null

    /** The last place name the dashboard found, for a refresh with no dashboard to ask. */
    @Volatile
    private var lastPlace: String? = null

    internal data class Face(val emoji: String, val text: String)

    /** What the file says. */
    internal data class Content(
        val fetchedAtMs: Long,
        val points: List<WeatherWidgetContent.Point>,
        /** Every [WeatherFace] by its key: the widget looks up each point's. */
        val faces: Map<String, Face>,
        /** How far ahead the curve looks: the rider's window. */
        val windowHours: Int,
        val windowLabel: String,
        val place: String,
        /** "Now", under the curve's left end. */
        val now: String,
        /** The "%1$d h ago" template; the widget knows the age, the app the language. */
        val hourAgo: String,
        /** Where the forecast is for, so a refresh with no fix asks about the same place. */
        val lat: Double,
        val lon: Double,
    )

    private class Last(val lat: Double, val lon: Double, val place: String)

    /** Everything the file is made from: the same again is not written, nor drawn, again. */
    private data class Key(
        val forecast: WeatherForecast,
        val weather: WeatherSettings,
        val advanced: AdvancedSettings,
        val speedUnit: String,
        val tempUnit: String,
        val language: String,
        val place: String,
    )

    /** Refreshes a placed widget's forecast each time the app leaves the screen. */
    fun start() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { refreshIfPlaced() }
        }
    }

    /**
     * Whatever the panel fetched, the widgets get too. A null [place] is a
     * name not looked up yet, so the last one stays, as Android's worker
     * keeps it.
     */
    fun publish(forecast: WeatherForecast, settings: AppSettings, place: String?) {
        if (place != null) lastPlace = place
        scope.launch { write(forecast, settings, place ?: knownPlace()) }
    }

    /** eucplanet://weather, a tap on a weather widget. True when [url] was that. */
    fun handleUrl(url: String): Boolean {
        if (!url.startsWith(PANEL_URL)) return false
        WeatherPanelLaunch.request()
        return true
    }

    /** The widget's refresh button. */
    internal fun pressRefresh() {
        scope.launch { held { refresh(force = true) } }
    }

    /** EUC_WEATHER_WIDGETS=<lat>,<lon>: the refresh button for a simulator, which has no fix (docs/IOS_SYNC.md). */
    internal fun refreshForHook(lat: Double, lon: Double) {
        scope.launch { Log.i(TAG, "EUC_WEATHER_WIDGETS: ${if (refresh(force = true, at = lat to lon)) "written" else "failed"}") }
    }

    /** [refresh] when the rider has a weather widget on a home screen; true when nothing is left to retry. */
    suspend fun refreshIfPlaced(): Boolean {
        if (!placed()) return true
        return held { refresh(force = false) }
    }

    /** True when a weather widget is on a home screen. A fetch for nobody is skipped, as on Android. */
    suspend fun placed(): Boolean {
        val bridge = IosWidgets.bridge ?: return false
        return suspendCoroutine { c -> bridge.weatherPlaced { c.resume(it) } }
    }

    /**
     * Fetches the forecast when the one in hand is old (always with [force])
     * and writes it for the widgets. It asks about where the phone is, or
     * where the last forecast was for: a widget must not turn the GPS on.
     * False when there is nowhere to ask about or the fetch failed.
     */
    private suspend fun refresh(force: Boolean, at: Pair<Double, Double>? = null): Boolean {
        val last = lastWritten()
        val here = IosLocation.fix.value ?: IosLocation.lastKnown.value
        val lat = at?.first ?: here?.latitude ?: last?.lat ?: return false
        val lon = at?.second ?: here?.longitude ?: last?.lon ?: return false
        val settings = IosAppGraph.settings.get()
        val w = settings.weather
        val repo = IosAppGraph.weather
        repo.ensureFresh(
            lat, lon, WeatherSource.byId(w.source), force,
            fine = WeatherWidgetContent.fine(w.windowHours),
        )
        val forecast = repo.forecast.value ?: return false
        write(forecast, settings, lastPlace ?: last?.place.orEmpty())
        return repo.error.value == null
    }

    private fun knownPlace(): String = lastPlace ?: lastWritten()?.place.orEmpty()

    /** Asks iOS for the seconds a fetch takes: a button press or leaving the screen gives the app very few. */
    private suspend fun <T> held(block: suspend () -> T): T {
        val app = UIApplication.sharedApplication
        var id: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        id = app.beginBackgroundTaskWithName("weather widgets") {
            app.endBackgroundTask(id)
            id = UIBackgroundTaskInvalid
        }
        try {
            return block()
        } finally {
            if (id != UIBackgroundTaskInvalid) app.endBackgroundTask(id)
        }
    }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    private suspend fun write(forecast: WeatherForecast, settings: AppSettings, place: String) = writing.withLock {
        val path = IosWidgets.path(FILE) ?: return@withLock
        // The dashboard publishes on every settings write; only a change is drawn.
        val key = Key(
            forecast, settings.weather, settings.advanced,
            Units.effectiveSpeedUnit(settings), Units.effectiveTempUnit(settings), settings.language, place,
        )
        if (key == lastKey) return@withLock
        val json = encode(content(forecast, settings, place))
        if (NSString.create(string = json).writeToFile(path, true, NSUTF8StringEncoding, null)) {
            lastKey = key
            IosWidgets.bridge?.reloadWeather()
        } else {
            Log.w(TAG, "could not write $path")
        }
    }

    /** Where and for which place the file on disk is, or null before the first forecast. */
    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    private fun lastWritten(): Last? {
        val path = IosWidgets.path(FILE) ?: return null
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: return null
        return try {
            val o = JSONObject(text.toString())
            Last(o.getDouble("lat"), o.getDouble("lon"), o.optString("place", ""))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun content(forecast: WeatherForecast, settings: AppSettings, place: String): Content {
        val speedUnit = Units.effectiveSpeedUnit(settings)
        val window = settings.weather.windowHours
        return Content(
            fetchedAtMs = forecast.fetchedAtMs,
            points = WeatherWidgetContent.points(forecast, settings, getString(Units.speedUnitRes(speedUnit))),
            faces = WeatherFace.entries.associate { it.key to Face(it.emoji, getString(it.textRes)) },
            windowHours = window,
            windowLabel = getString(Res.string.widget_weather_window_fmt, window),
            place = place,
            now = getString(Res.string.weather_now),
            hourAgo = getString(Res.string.widget_weather_hour_fmt),
            lat = forecast.lat,
            lon = forecast.lon,
        )
    }

    /** The file's text: what iosApp/Widgets/WeatherWidgets.swift decodes. Times are seconds since 1970. */
    internal fun encode(c: Content): String = JSONObject()
        .put("fetchedAt", c.fetchedAtMs / 1000)
        .put("window", c.windowHours * 3600)
        .put("stale", WeatherWidgetContent.STALE_MS / 1000)
        .put("windowLabel", c.windowLabel)
        .put("place", c.place)
        .put("now", c.now)
        .put("hourAgo", c.hourAgo)
        .put("lat", c.lat)
        .put("lon", c.lon)
        .put("faces", JSONObject().apply {
            c.faces.forEach { (key, f) -> put(key, JSONObject().put("emoji", f.emoji).put("text", f.text)) }
        })
        .put("points", JSONArray().apply {
            c.points.forEach { p ->
                put(
                    JSONObject().put("t", p.timeMs / 1000)
                        // One decimal is all the curve can show.
                        .put("score", (p.score * 10).roundToInt() / 10.0)
                        .put("label", p.scoreLabel)
                        .put("face", p.faceKey)
                        .put("temp", p.tempLabel)
                        .put("wind", p.windLabel)
                )
            }
        })
        .toString()
}
