package com.eried.eucplanet.app

import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.model.WidgetActionType
import com.eried.eucplanet.data.model.WidgetSettings
import com.eried.eucplanet.platform.JSONArray
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.widget.WidgetContent
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * WidgetKit's side of the home screen widgets, written in Swift
 * (iosApp/WidgetReloader.swift) and registered here at launch. Kotlin decides
 * what the widgets say and when they are drawn again.
 */
interface WidgetBridge {
    /** Asks iOS to draw every widget again from the file [IosWidgets] wrote. */
    fun reload()

    /** Asks iOS to draw the two weather widgets again from the file [IosWeatherWidgets] wrote. */
    fun reloadWeather()

    /** Tells [done] whether a weather widget is on a home screen. */
    fun weatherPlaced(done: (Boolean) -> Unit)
}

/**
 * The iPhone's home screen widgets: Android's EucWidget (three readings and
 * up to three buttons) and its button-only widgets, with the rider's own
 * slots (Settings, Home screen widget) and the same numbers, units and labels
 * ([WidgetContent]).
 *
 * A widget is drawn by the widget extension, a separate process that cannot
 * reach the app. The app writes what the widgets say into a file both can
 * read (the App Group container) and asks iOS to draw them again; the Swift
 * side (iosApp/Widgets/WheelWidgets.swift) only lays it out.
 *
 * What iOS does differently from Android: a widget is not repainted every
 * second. iOS rations the reloads of an app that is not on screen, so the
 * buttons and the connection follow at once and the numbers every few
 * minutes; the widget says when they were read. Buttons need iOS 17, and a
 * press runs in the app (RideActionIntent), as the Live Activity's do.
 */
object IosWidgets {
    private const val TAG = "IosWidgets"

    /** The App Group both the app and the widget extension are entitled to (the two .entitlements files in iosApp). */
    const val GROUP = "group.com.eried.eucplanet.ios"
    const val FILE = "widgets.json"

    /** How often the numbers alone move a widget. iOS allows a background app a few dozen reloads a day. */
    internal const val REFRESH_MS = 5 * 60 * 1000L

    var bridge: WidgetBridge? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var latest: Inputs? = null
    private var lastShape: Shape? = null
    private var lastAt = 0L

    /** The rider's choices a widget reads. */
    private data class Prefs(
        val widget: WidgetSettings,
        val voiceOn: Boolean,
        val speedUnit: String,
        val distUnit: String,
        val tempUnit: String,
    )

    private data class State(val locked: Boolean, val recording: Boolean, val lockedDown: Boolean, val prefs: Prefs)

    private data class Inputs(val connected: Boolean, val data: WheelData, val state: State)

    /** Everything but the numbers: a change here is drawn at once. */
    private data class Shape(val connected: Boolean, val lightOn: Boolean, val state: State)

    /** [enabled] now, [enabledIdle] once no wheel answers (see [Content.statusIdle]). */
    internal data class Button(val key: String, val label: String, val enabled: Boolean, val enabledIdle: Boolean)

    /** What the file says. [buttons] and [standalone] keep one entry per slot, an empty slot as [WidgetActionType.NONE]. */
    internal data class Content(
        val connected: Boolean,
        val status: String,
        /**
         * What a widget says once its numbers are too old to show: the app
         * was ended mid-ride, or iOS did not draw the widget again. It falls
         * back to the disconnected look by itself, with no word from the app.
         */
        val statusIdle: String,
        val values: List<String>,
        val captions: List<String>,
        val buttons: List<Button>,
        val standalone: List<Button>,
        /** The widget gallery's names and descriptions, in the app's language. */
        val names: Map<String, String>,
    )

    fun follow(link: IosWheelLink, data: StateFlow<WheelData>) {
        scope.launch {
            // Re-read when the rider changes a slot or a unit, not on every settings write.
            val prefs = IosAppGraph.settings.settings.map {
                Prefs(
                    it.widget, it.voiceEnabled,
                    Units.effectiveSpeedUnit(it), Units.effectiveDistanceUnit(it), Units.effectiveTempUnit(it),
                )
            }.distinctUntilChanged()
            val state = combine(
                IosAppGraph.wheel.locked, IosTrips.recording, IosLockdown.controller.engaged, prefs,
            ) { locked, recording, lockedDown, p -> State(locked, recording, lockedDown, p) }
            combine(link.connectionState, data, state) { c, d, s ->
                Inputs(c == ConnectionState.CONNECTED, d, s)
            }.collect {
                latest = it
                publish(it, force = false)
            }
        }
        // The rider is about to look at the home screen: the freshest numbers,
        // and the labels in the language the app is in now.
        NSNotificationCenter.defaultCenter.addObserverForName(
            UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue,
        ) { _ ->
            scope.launch { latest?.let { publish(it, force = true) } }
        }
    }

    /**
     * The widget's voice button, the one action the Live Activity does not
     * have: it flips the periodic announcements, an app setting, so it works
     * with no wheel connected. The settings change draws the widgets again.
     */
    internal fun pressVoice() {
        scope.launch { IosAppGraph.settings.update { it.copy(voiceEnabled = !it.voiceEnabled) } }
    }

    @OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
    private suspend fun publish(inputs: Inputs, force: Boolean) {
        // Legal Mode Lockdown stops the widgets, as on Android.
        if (inputs.state.lockedDown) return
        val shape = Shape(inputs.connected, inputs.connected && inputs.data.lightOn, inputs.state)
        val now = System.currentTimeMillis()
        if (!force && shape == lastShape && now - lastAt < REFRESH_MS) return
        val path = path(FILE) ?: return
        lastShape = shape
        lastAt = now
        val json = encode(content(inputs), now / 1000)
        if (NSString.create(string = json).writeToFile(path, true, NSUTF8StringEncoding, null)) {
            bridge?.reload()
        } else {
            Log.w(TAG, "could not write $path")
        }
    }

    /** Where the widgets read from, or null on a build without the App Group (no widgets then). */
    @OptIn(ExperimentalForeignApi::class)
    private val folder: String? by lazy {
        NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(GROUP)?.path
            .also { if (it == null) Log.w(TAG, "No App Group container, the widgets stay empty") }
    }

    /** [name] in the folder the widgets read from. */
    internal fun path(name: String): String? = folder?.let { "$it/$name" }

    private suspend fun content(i: Inputs): Content {
        val s = i.state
        val p = s.prefs
        // Disconnected shows dashes rather than the last live numbers, which a
        // rider glancing at the home screen would read as current.
        val metrics = WidgetContent.metrics(
            csv = p.widget.metrics,
            data = i.data.takeIf { i.connected },
            speedUnit = p.speedUnit,
            speedUnitLabel = getString(Units.speedUnitRes(p.speedUnit)),
            distUnit = p.distUnit,
            tempUnit = p.tempUnit,
            phoneBattery = { IosPhoneDevice.batteryPercent().coerceIn(0, 100) },
            text = { getString(it) },
        )
        val lightOn = i.connected && i.data.lightOn
        val buttons = ArrayList<Button>()
        val standalone = ArrayList<Button>()
        for ((csv, into) in listOf(p.widget.actions to buttons, p.widget.standaloneActions to standalone)) {
            for (key in WidgetActionType.slots(csv)) {
                val label = WidgetContent.buttonLabel(key, lightOn, s.locked, s.recording, p.voiceOn)
                into.add(
                    if (label == null) Button(WidgetActionType.NONE, "", false, false)
                    else Button(
                        key, getString(label),
                        WidgetContent.enabled(key, i.connected), WidgetContent.enabled(key, connected = false),
                    )
                )
            }
        }
        return Content(
            connected = i.connected,
            status = getString(if (i.connected) Res.string.widget_connected else Res.string.widget_disconnected),
            statusIdle = getString(Res.string.widget_disconnected),
            values = metrics.values,
            captions = metrics.captions,
            buttons = buttons,
            standalone = standalone,
            names = mapOf(
                "wheel" to getString(Res.string.widget_euc_label),
                "wheelDesc" to getString(Res.string.widget_description),
                "act1" to getString(Res.string.widget_act_1_label),
                "act2" to getString(Res.string.widget_act_2_label),
                "act3" to getString(Res.string.widget_act_3_label),
                "act12" to getString(Res.string.widget_act_12_label),
                "act23" to getString(Res.string.widget_act_23_label),
                "actDesc" to getString(Res.string.widget_act_1_desc),
                "actsDesc" to getString(Res.string.widget_act_12_desc),
                "unset" to getString(Res.string.widget_button_unset),
                // The weather widgets' own file comes with the first forecast;
                // what they say before one is here.
                "weatherScore" to getString(Res.string.widget_weather_score_label),
                "weatherScoreDesc" to getString(Res.string.widget_weather_score_desc),
                "weatherForecast" to getString(Res.string.widget_weather_compact_label),
                "weatherForecastDesc" to getString(Res.string.widget_weather_graph_desc),
                "weatherQuestion" to getString(Res.string.widget_weather_question),
                "weatherNever" to getString(Res.string.widget_weather_never),
                "weatherNeverShort" to getString(Res.string.widget_weather_never_short),
            ),
        )
    }

    /** The file's text: what iosApp/Widgets/WheelWidgets.swift decodes. [atSeconds] is when the numbers were read. */
    internal fun encode(c: Content, atSeconds: Long): String {
        fun buttons(list: List<Button>) = JSONArray().apply {
            list.forEach { b ->
                put(
                    JSONObject().put("key", b.key).put("label", b.label)
                        .put("enabled", b.enabled).put("enabledIdle", b.enabledIdle)
                )
            }
        }
        return JSONObject()
            .put("at", atSeconds)
            .put("connected", c.connected)
            .put("status", c.status)
            .put("statusIdle", c.statusIdle)
            .put("metrics", JSONArray().apply {
                c.values.forEachIndexed { n, value ->
                    put(JSONObject().put("value", value).put("caption", c.captions.getOrElse(n) { "" }))
                }
            })
            .put("buttons", buttons(c.buttons))
            .put("standalone", buttons(c.standalone))
            .put("names", JSONObject().apply { c.names.forEach { (k, v) -> put(k, v) } })
            .toString()
    }
}
