package com.eried.eucplanet.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
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

    override fun createHostValidator(): HostValidator =
        // Debug and branch builds talk to any host so the desktop head unit
        // works; a store build only to the hosts Google signs.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

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
                CarRideContent(state, dark.value, area)
            }
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
            return RideScreen(carContext)
        }

        override fun onCarConfigurationChanged(newConfiguration: Configuration) {
            dark.value = carContext.isDarkMode
        }
    }

    /** The map screen: Android Auto's frame around our drawing. */
    private inner class RideScreen(carContext: CarContext) : Screen(carContext) {
        private val navManager = carContext.getCarService(NavigationManager::class.java)

        init {
            navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() = navigationEngine.stop()
            })
            // Redraw the frame only when something it shows changes: button
            // labels (light, recording) and the turn card. The live map and
            // stats repaint on the surface without touching the template.
            lifecycleScope.launch {
                combine(
                    wheelRepository.wheelData.map { it.lightOn }.distinctUntilChanged(),
                    tripRepository.recording,
                    navigationEngine.navState,
                    settingsRepository.settings.map { it.androidAuto }.distinctUntilChanged(),
                ) { _, _, nav, _ -> nav.active }
                    .collect { active ->
                        if (active) navManager.navigationStarted() else navManager.navigationEnded()
                        invalidate()
                    }
            }
        }

        // PROTOTYPE (proto/aa-category): POI category instead of NAVIGATION, to
        // find out whether a non-navigation car app can still draw our own
        // pixels, and how much of the screen it gets when it does.
        override fun onGetTemplate(): Template {
            val nav = navigationEngine.navState.value
            val wheel = wheelRepository.wheelData.value
            val content = androidx.car.app.model.PaneTemplate.Builder(
                androidx.car.app.model.Pane.Builder()
                    .addRow(
                        androidx.car.app.model.Row.Builder()
                            .setTitle("Speed")
                            .addText(String.format(java.util.Locale.US, "%.1f km/h", wheel.speed))
                            .build()
                    )
                    .addRow(
                        androidx.car.app.model.Row.Builder()
                            .setTitle("Battery")
                            .addText("${'$'}{wheel.batteryPercent}%")
                            .build()
                    )
                    .build()
            ).setTitle("EUC Planet").build()
            return androidx.car.app.navigation.model.MapWithContentTemplate.Builder()
                .setContentTemplate(content)
                .setActionStrip(actionStrip(nav.active))
                .build()
        }

        private fun actionStrip(navigating: Boolean): ActionStrip {
            val strip = ActionStrip.Builder()
            // Navigate always leads: a navigation app without it makes no
            // sense. While guiding, the same slot ends the route.
            strip.addAction(
                if (navigating) {
                    Action.Builder().setTitle(carContext.getString(R.string.nav_stop_short))
                        .setOnClickListener { navigationEngine.stop() }.build()
                } else {
                    Action.Builder().setTitle(carContext.getString(R.string.car_navigate))
                        .setOnClickListener { screenManager.push(PlacesScreen(carContext)) }.build()
                }
            )
            val s = settingsState.value
            val lightOn = wheelRepository.wheelData.value.lightOn
            val recording = tripRepository.recording.value
            AndroidAutoSettings.actionSlots(s.androidAuto.actions).forEachIndexed { i, key ->
                val pi = EucWidget.actionIntentFor(carContext, key, 900 + i, recording) ?: return@forEachIndexed
                val label = EucWidget.buttonLabel(
                    carContext, key, lightOn = lightOn, locked = wheelRepository.locked.value,
                    recording = recording, voiceOn = s.voiceEnabled,
                )
                val b = Action.Builder().setTitle(label).setOnClickListener { pi.send() }
                EucWidget.iconFor(key).takeIf { it != 0 }?.let {
                    b.setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, it)).build())
                }
                strip.addAction(b.build())
            }
            return strip.build()
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
