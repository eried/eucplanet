package com.eried.eucplanet.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.validation.HostValidator
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.hud.protocol.HudDebug
import com.eried.eucplanet.data.model.WidgetMetricType
import com.eried.eucplanet.util.Units
import com.eried.eucplanet.widget.WidgetMetricFormat
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AndroidAutoSettings
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.nav.RoutingService
import com.eried.eucplanet.widget.EucWidget
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * Android Auto. The car screen shows the navigator's map with the rider's
 * stats beside it, and the wheel's buttons in Android Auto's action strip.
 * Riders use it on motorcycle-style Android Auto displays, which connect by
 * themselves where the Motoeye HUD needs a Wi-Fi setup.
 *
 * Every button goes through the same intent the notification and the home
 * widget send, so a car tap behaves exactly like those taps do.
 */
@AndroidEntryPoint
class EucCarAppService : CarAppService() {

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var navigationEngine: NavigationEngine
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var routingService: RoutingService
    @Inject lateinit var weatherRepository: com.eried.eucplanet.weather.WeatherRepository

    private companion object {
        const val TAG = "EucCarAppService"
        const val DEBUG_API_LEVEL_PROP = "debug.eucplanet.car.apilevel"
        /** Used only if the host refuses to say. The Car App Library's own
         *  fallback for a pane is 4 rows. */
        const val DEFAULT_PANE_ROWS = 4
    }

    override fun createHostValidator(): HostValidator =
        // Debug and branch builds talk to any host so the desktop head unit
        // works; a store build only to the hosts Google signs.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    /** The car surface no longer draws the map. On a head unit the rider
     *  already has Maps beside us, so a second, worse map in our card is noise,
     *  and the point of the card is numbers readable at a glance. Kept as a
     *  value rather than deleted so CarRideContent keeps one call shape and the
     *  map path stays available to the phone overlay. */
    private val mapVisible = mutableStateOf(false)

    /** Sanitized settings for the templates, which cannot suspend. */
    private lateinit var settingsState: kotlinx.coroutines.flow.StateFlow<com.eried.eucplanet.data.model.AppSettings>

    override fun onCreateSession(): Session = RideSession()

    private inner class RideSession : Session() {
        private val dark = mutableStateOf(false)

        override fun onCreateScreen(intent: Intent): Screen {
            dark.value = carContext.isDarkMode
            tripRepository.startLocationUpdates()
            val scope = lifecycleScope
            settingsState = settingsRepository.settings
                .stateIn(scope, SharingStarted.Eagerly, com.eried.eucplanet.data.model.AppSettings())
            val state = CarRideState(
                wheel = wheelRepository.wheelData,
                connection = wheelRepository.connectionState,
                location = tripRepository.currentLocation,
                route = navigationEngine.activeLeg.map { it?.geometry }
                    .stateIn(scope, SharingStarted.Eagerly, navigationEngine.activeLeg.value?.geometry),
                settings = settingsState,
                phoneBattery = {
                    getSystemService(BatteryManager::class.java)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                },
            )
            val renderer = CarSurfaceRenderer(carContext) { area ->
                CarRideContent(state, dark.value, area, mapVisible.value)
            }
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
            return RideScreen(carContext)
        }

        override fun onCarConfigurationChanged(newConfiguration: Configuration) {
            dark.value = carContext.isDarkMode
        }
    }

    /** The car screen: Android Auto's frame around our numbers. */
    private inner class RideScreen(carContext: CarContext) : Screen(carContext) {

        init {
            // No NavigationManager. It is a navigation-app API and this app is
            // not one any more: the manifest carries MAP_TEMPLATES, not
            // NAVIGATION_TEMPLATES, so every navigationStarted() call went to
            // the host without the permission that authorises it. The call
            // only fired once the rider actually started a route, which is
            // exactly when a tester reported "app closes when navigating using
            // the built in EUC Planet navigation feature". Stopping navigation
            // from the car screen goes with it; the rider stops it on the
            // phone, where they started it.
            //
            // Redraw the frame when something it shows changes: button labels
            // (light, recording) and, now that the rider's numbers live in the
            // content pane rather than on the surface, the numbers themselves.
            //
            // The pane is a template, so it only changes when we ask for it.
            // Telemetry arrives several times a second and the host rate-limits
            // template updates, so sample it: one refresh a second reads as live
            // on a car screen and stays well inside what the host will accept.
            lifecycleScope.launch {
                combine(
                    wheelRepository.wheelData.map { it.lightOn }.distinctUntilChanged(),
                    tripRepository.recording,
                    navigationEngine.navState,
                    settingsRepository.settings.map { it.androidAuto }.distinctUntilChanged(),
                    merge(
                        wheelRepository.connectionState,
                        wheelRepository.wheelData.sample(1_000L),
                    ),
                ) { _, _, _, _, _ -> Unit }
                    .collect { invalidate() }
            }
        }

        /**
         * POI, not NAVIGATION, and the rider's numbers live in the car's own
         * content pane rather than in a corner of our map.
         *
         * Two reasons, both from testers. Android Auto runs one navigation app
         * at a time, so being one meant EUC Planet vanished the moment someone
         * opened Maps. And the stats drawn on our surface were too small to
         * read: the host draws a content pane large and legible for free, and
         * NF-2 wants the surface carrying map content anyway.
         */
        override fun onGetTemplate(): Template {
            val s = settingsState.value
            val wheel = wheelRepository.wheelData.value
            val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
            val pane = Pane.Builder()
            // A pane does not scroll while driving, and the host caps how many
            // rows it will draw. We used to add four metrics and hope; a tester
            // on a MotoEye reported "cannot scroll below the 3rd metric",
            // which is this cap silently eating the rest. Ask the host what it
            // will take and never build more than that.
            val limit = runCatching {
                carContext.getCarService(ConstraintManager::class.java)
                    .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_PANE)
            }.getOrDefault(DEFAULT_PANE_ROWS).coerceAtLeast(1)

            val rows = mutableListOf<Row>()
            if (connected) {
                AndroidAutoSettings.metricSlots(s.androidAuto.metrics).forEach { key ->
                    val type = WidgetMetricType.byKey(key) ?: return@forEach
                    rows += Row.Builder()
                        .setTitle(carContext.getString(type.pickerLabel))
                        .addText(carMetricText(type, wheel, s))
                        .build()
                }
            } else {
                rows += Row.Builder()
                    .setTitle(carContext.getString(R.string.car_waiting_wheel)).build()
            }
            // Weather goes last and only if there is room left. It is what the
            // category asks for, but the rider chose those metrics and a line
            // about the sky must not push one of them off the screen.
            if (rows.size < limit) weatherRow()?.let { rows += it }
            rows.take(limit).forEach { pane.addRow(it) }
            Log.i(TAG, "pane rows ${rows.size.coerceAtMost(limit)} of limit $limit")
            val built = pane.build()
            val title = getString(R.string.app_name)

            // MapWithContentTemplate is RequiresCarApi(7) and still marked
            // experimental; MapController is RequiresCarApi(5). We advertise
            // minCarApiLevel 1, so on an older head unit building either one
            // throws and Android Auto shows "EUC Planet has encountered an
            // unexpected error" with nothing else to go on. A tester hit exactly
            // that on a MotoEye: the car emulator negotiates api 7 and hid it.
            //
            // Below 7 we fall back to the pane on its own. No map and no map
            // toggle, but the rider's numbers are the point of this screen and
            // the host still draws them large.
            // `setprop debug.eucplanet.car.apilevel 4` forces the older-host
            // path on a machine whose host reports 7, so the fallback can be
            // seen rather than assumed. Shipping an unseen branch is what put
            // "unexpected error" on a tester's screen in the first place.
            val apiLevel = HudDebug.read(DEBUG_API_LEVEL_PROP)?.toIntOrNull()
                ?: carContext.carAppApiLevel
            Log.i(TAG, "car host api level $apiLevel")
            return if (apiLevel >= 7) {
                // The map is gone from the car screen: on a head unit the rider
                // is already looking at Maps, and a second, worse map in our
                // card is noise. The surface carries the big numbers instead.
                //
                // The MapController stays even with no map and no map actions.
                // Without one the host keeps the surface to itself and never
                // calls our SurfaceCallback, which leaves the whole area blank
                // white. Seen on the car emulator, not guessed.
                MapWithContentTemplate.Builder()
                    .setContentTemplate(PaneTemplate.Builder(built).setTitle(title).build())
                    .apply { actionStrip(false)?.let { setActionStrip(it) } }
                    .setMapController(MapController.Builder().build())
                    .build()
            } else {
                PaneTemplate.Builder(built)
                    .setTitle(title)
                    .apply { actionStrip(false, iconOnly = true, max = 2)?.let { setActionStrip(it) } }
                    .build()
            }
        }

        /**
         * The rider's local weather, or null before the first forecast lands.
         *
         * Reads the cache the dashboard already fills, so the car screen never
         * fetches on its own: a head unit that has only just connected simply
         * shows no weather line until the phone has one, which is better than
         * a row that says nothing.
         */
        private fun weatherRow(): Row? {
            val now = weatherRepository.forecast.value?.hours?.firstOrNull() ?: return null
            val s = settingsState.value
            val tempUnit = Units.effectiveTempUnit(s)
            val speedUnit = Units.effectiveSpeedUnit(s)
            val temp = Units.temperature(now.tempC, tempUnit)
            // Wind arrives in m/s; the rider's speed unit is what they read all
            // day, so convert through km/h rather than inventing a third unit.
            val wind = Units.speed(now.windMs * 3.6f, speedUnit)
            return Row.Builder()
                .setTitle(carContext.getString(R.string.car_weather))
                .addText(
                    "%.0f %s  %.0f %s".format(
                        temp, Units.tempUnit(tempUnit),
                        wind, Units.speedUnit(carContext, speedUnit),
                    )
                )
                .build()
        }

        /** One metric, formatted exactly as the surface and the widget do. */
        private fun carMetricText(
            type: WidgetMetricType,
            wheel: com.eried.eucplanet.data.model.WheelData,
            s: com.eried.eucplanet.data.model.AppSettings,
        ): String {
            val speedUnit = Units.effectiveSpeedUnit(s)
            val distUnit = Units.effectiveDistanceUnit(s)
            val tempUnit = Units.effectiveTempUnit(s)
            val phoneBattery = getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            return WidgetMetricFormat.value(type, wheel, speedUnit, distUnit, tempUnit, phoneBattery) +
                " " + WidgetMetricFormat.unit(carContext, type, speedUnit, distUnit, tempUnit)
        }

        /**
         * [iconOnly] drops the labels. PaneTemplate allows exactly one titled
         * action, so the three the rider configured crash it ("Action list
         * exceeded max number of 1 actions with custom titles"). Each limit is
         * per template and none of them are the same: the map strip allows
         * none, a pane allows one, navigation allowed four.
         *
         * Every action in the widget registry carries an icon, so dropping the
         * labels costs recognisability, not function. An action without an icon
         * is left out rather than risking the limit.
         *
         * [max] caps the total, which is a SEPARATE limit from the titled one
         * and has its own message ("Action list exceeded max number of 2
         * actions"). Each template sets its own and none of them agree: the map
         * strip takes no titled action at all, a pane takes two actions of
         * which one may be titled, navigation took four. They throw rather than
         * degrade, so every one of them has to be found by running the path.
         */
        private fun actionStrip(
            navigating: Boolean,
            iconOnly: Boolean = false,
            max: Int = Int.MAX_VALUE,
        ): ActionStrip? {
            val strip = ActionStrip.Builder()
            // No Navigate. This stopped being a navigation app, and the action
            // was still leading the strip: on a pane, which allows one titled
            // action, it took the only slot and left the rider one button of
            // the three they chose. The three are now all theirs.
            var added = 0
            val s = settingsState.value
            val lightOn = wheelRepository.wheelData.value.lightOn
            val recording = tripRepository.recording.value
            AndroidAutoSettings.actionSlots(s.androidAuto.actions).forEachIndexed { i, key ->
                val pi = EucWidget.actionIntentFor(carContext, key, 900 + i, recording) ?: return@forEachIndexed
                val label = EucWidget.buttonLabel(
                    carContext, key, lightOn = lightOn, locked = wheelRepository.locked.value,
                    recording = recording, voiceOn = s.voiceEnabled,
                )
                if (added >= max) return@forEachIndexed
                val icon = EucWidget.iconFor(key).takeIf { it != 0 }
                if (iconOnly && icon == null) return@forEachIndexed
                val b = Action.Builder().setOnClickListener { pi.send() }
                if (!iconOnly) b.setTitle(label)
                icon?.let {
                    b.setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, it)).build())
                }
                strip.addAction(b.build())
                added++
            }
            // Null, not an empty strip. A rider who sets all three buttons to
            // None gets no strip at all; building an empty one throws
            // "Action strip must contain at least one action" and takes the
            // whole car screen with it. A tester hit exactly that: no buttons,
            // a black screen, and a crash whenever anything forced a redraw.
            return if (added == 0) null else strip.build()
        }
    }

    /** Home and Work, the places the route builder saves. */
    private inner class PlacesScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template {
            val s = settingsState.value
            val places = listOfNotNull(
                place(s.navHomeJson, carContext.getString(R.string.car_place_home)),
                place(s.navWorkJson, carContext.getString(R.string.car_place_work)),
            )
            val list = ItemList.Builder()
            if (places.isEmpty()) list.setNoItemsMessage(carContext.getString(R.string.car_no_places))
            places.forEach { p ->
                list.addItem(
                    Row.Builder().setTitle(p.name).setOnClickListener {
                        navigateTo(p)
                        screenManager.pop()
                    }.build()
                )
            }
            return ListTemplate.Builder()
                .setTitle(carContext.getString(R.string.car_places_title))
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build()
        }

        private fun place(json: String, fallbackName: String): Waypoint? = runCatching {
            if (json.isBlank()) null
            else JSONObject(json).let {
                Waypoint(it.getDouble("lat"), it.getDouble("lng"), it.optString("name").ifBlank { fallbackName })
            }
        }.getOrNull()
    }

    /** Routes from where the rider is now, the way the navigator's own Start does. */
    private fun navigateTo(dest: Waypoint) {
        val loc = tripRepository.currentLocation.value ?: return
        kotlinx.coroutines.MainScope().launch {
            val s = settingsRepository.get()
            val mode = TravelMode.fromName(s.navDefaultTravelMode)
            val wps = listOf(Waypoint(loc.latitude, loc.longitude), dest)
            val route = routingService.route(dest.name, wps, mode, RoutingService.effectiveRouterUrl(s.navRouterUrl))
                ?: RoutingService.straightLineRoute(dest.name, wps)
            navigationEngine.start(
                route, if (mode == TravelMode.STRAIGHT) NavMode.TREASURE_HUNT else NavMode.TURN_BY_TURN
            )
        }
    }
}
