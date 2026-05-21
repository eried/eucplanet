package com.eried.eucplanet.nav

import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.ArrowDir
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.model.Maneuver
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.NavState
import com.eried.eucplanet.data.model.Proximity
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.data.model.TurnType
import com.eried.eucplanet.data.model.Waypoint
import com.eried.eucplanet.data.model.toArrow
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.service.VoiceService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The live-guidance brain. Holds the active [NavRoute], consumes the 1 Hz GPS
 * fixes published by [TripRepository.currentLocation] and turns them into a
 * [NavState] the popup (phone + watch) renders, plus spoken cues.
 *
 * Heading is derived from the rider's *moving* trace — the displacement over
 * the last few seconds of fixes where they were actually riding — rather than
 * the phone compass, which is unreliable on a wheel. Until enough movement has
 * accumulated the popup just says "start riding".
 *
 * Two modes:
 *  - [NavMode.TURN_BY_TURN] — follows the routed polyline, announcing each
 *    maneuver and flagging "wrong way" / re-routing when the rider drifts off.
 *  - [NavMode.TREASURE_HUNT] — no street-by-street steps; just the direction
 *    and distance to the next goal, with warmer/colder proximity cues.
 */
@Singleton
class NavigationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tripRepository: TripRepository,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val routingService: RoutingService,
    private val voiceService: VoiceService
) {
    companion object {
        private const val TAG = "NavigationEngine"

        // Heading from trace.
        private const val HEADING_WINDOW_MS = 8_000L
        private const val FIX_BUFFER_MS = 14_000L
        private const val MIN_HEADING_DISP_M = 8.0

        // "Moving" thresholds — either GPS speed (m/s) or wheel speed (km/h).
        private const val MOVING_MS = 1.2
        private const val MOVING_KMH = 4.0

        // Turn-by-turn announce distances.
        private const val PREPARE_DIST_M = 200.0
        private const val EXECUTE_DIST_M = 30.0

        // Off-route handling.
        private const val OFF_ROUTE_GRACE_MS = 3_000L
        private const val WRONG_WAY_COOLDOWN_MS = 14_000L
        private const val REROUTE_AFTER_MS = 12_000L

        // How long the "arrived" banner lingers before the popup self-clears.
        private const val ARRIVAL_DISMISS_MS = 9_000L

        // Treasure Hunt voice cadence + proximity hysteresis.
        private const val HUNT_VOICE_INTERVAL_MS = 20_000L
        private const val PROX_BAND_M = 4.0
    }

    // Every mutation of the engine's runtime state happens inside a coroutine
    // launched on [scope]; pinning that scope to a single thread serialises the
    // GPS-fix handler, re-routing and the start/stop paths so none of them race.
    private val engineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nav-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineDispatcher)

    private val _navState = MutableStateFlow(NavState())
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    val isActive: Boolean get() = _navState.value.active

    @Volatile private var initJob: Job? = null
    @Volatile private var collectJob: Job? = null
    @Volatile private var rerouteJob: Job? = null
    @Volatile private var arrivalJob: Job? = null
    @Volatile private var activeRoute: NavRoute? = null
    private var navMode: NavMode = NavMode.TURN_BY_TURN

    // Settings snapshot, refreshed on start().
    private var imperial = false
    private var voiceEnabled = true
    private var arrivalRadiusM = 25.0
    private var offRouteToleranceM = 40.0
    private var routerUrl = RoutingService.DEFAULT_ROUTER

    // --- trace / heading state ---
    private data class Fix(val point: GeoPoint, val timeMs: Long, val moving: Boolean)
    private val recentFixes = ArrayDeque<Fix>()
    private var heading: Double? = null

    // --- turn-by-turn announce tracking ---
    private var preparedManeuver = -1
    private var executedManeuver = -1
    private var offRouteSinceMs = 0L
    private var backOnRouteSinceMs = 0L
    private var lastWrongWayMs = 0L
    private var rerouteInFlight = false
    private var waypointAlongM: List<Double> = emptyList()

    // --- treasure-hunt state ---
    private var currentGoal = 0
    private var lastGoalDistM = Double.NaN
    private var lastProximity: Proximity? = null
    private var lastHuntVoiceMs = 0L

    private var arrivalHandled = false

    /** Begins guidance. Must be called while the app is in the foreground. */
    fun start(route: NavRoute, mode: NavMode) {
        if (route.waypoints.size < 2 && route.geometry.size < 2) {
            Log.w(TAG, "start() ignored — route has nothing to navigate")
            return
        }
        // Start GPS + the foreground service synchronously, while the caller is
        // still in the foreground. Deferring this into the coroutine below risks
        // ForegroundServiceStartNotAllowedException on Android 12+ if the
        // builder screen closes before the coroutine gets scheduled.
        tripRepository.startLocationUpdates()
        runCatching {
            context.startForegroundService(
                Intent(context, com.eried.eucplanet.service.WheelService::class.java)
                    .setAction(com.eried.eucplanet.service.WheelService.ACTION_START_NAVIGATION)
            )
        }
        stop()
        initJob = scope.launch {
            val s = settingsRepository.get()
            imperial = s.imperialUnits
            voiceEnabled = s.navVoiceEnabled
            arrivalRadiusM = s.navArrivalRadiusM.toDouble()
            offRouteToleranceM = s.navOffRouteToleranceM.toDouble()
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)

            activeRoute = route
            navMode = mode
            resetRuntimeState()
            waypointAlongM = if (route.geometry.size >= 2) {
                route.waypoints.mapNotNull {
                    GeoMath.nearestOnPolyline(it.point(), route.geometry)?.alongM
                }
            } else emptyList()

            _navState.value = NavState(
                active = true,
                mode = mode,
                waiting = true,
                primaryText = context.getString(R.string.nav_start_riding),
                goalIndex = 1,
                goalCount = route.waypoints.size.coerceAtLeast(1)
            )

            if (voiceEnabled) {
                voiceService.announceEvent(context.getString(R.string.voice_nav_start_riding))
            }

            collectJob = scope.launch {
                tripRepository.currentLocation.collect { loc -> if (loc != null) onFix(loc) }
            }
            Log.i(TAG, "Navigation started — mode=$mode, ${route.waypoints.size} stops")
        }
    }

    /** Ends guidance and clears the popup. Safe to call from any thread. */
    fun stop() {
        initJob?.cancel(); initJob = null
        collectJob?.cancel(); collectJob = null
        rerouteJob?.cancel(); rerouteJob = null
        arrivalJob?.cancel(); arrivalJob = null
        activeRoute = null
        _navState.value = NavState(active = false)
        // Re-assert the cleared state on the engine thread so a fix that was
        // already mid-flight when we cancelled can't leave a stale frame behind.
        scope.launch { _navState.value = NavState(active = false) }
        Log.i(TAG, "Navigation stopped")
    }

    fun setMinimized(minimized: Boolean) {
        _navState.value = _navState.value.copy(minimized = minimized)
    }

    /**
     * Re-opens the centred popup — used by the dashboard navigator button while
     * guidance is running so the rider can act on it (e.g. end navigation).
     * Un-minimizes and bumps [NavState.popupTick] to restart the show/timeout.
     */
    fun requestPopup() {
        _navState.value = _navState.value.copy(
            minimized = false,
            popupTick = _navState.value.popupTick + 1
        )
    }

    private fun resetRuntimeState() {
        recentFixes.clear()
        heading = null
        preparedManeuver = -1
        executedManeuver = -1
        offRouteSinceMs = 0L
        backOnRouteSinceMs = 0L
        lastWrongWayMs = 0L
        rerouteInFlight = false
        currentGoal = 0
        lastGoalDistM = Double.NaN
        lastProximity = null
        lastHuntVoiceMs = 0L
        arrivalHandled = false
    }

    // --- per-fix processing ------------------------------------------------------

    private fun onFix(loc: Location) {
        val route = activeRoute ?: return
        val now = System.currentTimeMillis()
        val point = GeoPoint(loc.latitude, loc.longitude)

        val gpsSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val wheelKmh = abs(wheelRepository.wheelData.value.speed).toDouble()
        val moving = gpsSpeed > MOVING_MS || wheelKmh > MOVING_KMH

        recentFixes.addLast(Fix(point, now, moving))
        while (recentFixes.isNotEmpty() && now - recentFixes.first().timeMs > FIX_BUFFER_MS) {
            recentFixes.removeFirst()
        }
        updateHeading(now)

        val h = heading
        if (h == null) {
            // No travel direction yet — ask the rider to get going.
            _navState.value = _navState.value.copy(
                waiting = true,
                arrow = ArrowDir.STRAIGHT,
                primaryText = context.getString(R.string.nav_start_riding),
                distanceText = ""
            )
            return
        }

        when (navMode) {
            NavMode.TURN_BY_TURN -> computeTurnByTurn(route, point, h, now)
            NavMode.TREASURE_HUNT -> computeTreasureHunt(route, point, h, now)
        }
    }

    /** Travel heading = bearing of the net displacement over the recent moving trace. */
    private fun updateHeading(now: Long) {
        val window = recentFixes.filter { now - it.timeMs <= HEADING_WINDOW_MS && it.moving }
        if (window.size >= 2) {
            val first = window.first().point
            val last = window.last().point
            if (GeoMath.distanceM(first, last) >= MIN_HEADING_DISP_M) {
                heading = GeoMath.bearingDeg(first, last)
            }
        }
    }

    // --- turn-by-turn ------------------------------------------------------------

    private fun computeTurnByTurn(route: NavRoute, point: GeoPoint, heading: Double, now: Long) {
        // A route with no maneuvers (STRAIGHT mode, or a routing fallback) is
        // guided like a homing line toward the final waypoint.
        if (route.maneuvers.isEmpty() || route.geometry.size < 2) {
            guideHoming(route, point, heading)
            return
        }

        val hit = GeoMath.nearestOnPolyline(point, route.geometry) ?: return
        val distToEnd = (route.totalDistanceM - hit.alongM).coerceAtLeast(0.0)

        if (!arrivalHandled && distToEnd <= arrivalRadiusM) {
            handleArrival()
            return
        }

        handleOffRoute(route, point, hit.distanceM > offRouteToleranceM, now)

        // The next maneuver is the first one still ahead on the line.
        val nextIndex = route.maneuvers.indexOfFirst { it.distanceFromStartM > hit.alongM + 5.0 }
        val next: Maneuver? = route.maneuvers.getOrNull(nextIndex)
        val distToTurn = ((next?.distanceFromStartM ?: route.totalDistanceM) - hit.alongM)
            .coerceAtLeast(0.0)

        if (next != null) announceManeuver(nextIndex, next, distToTurn)

        // Count only the destination-side waypoints already passed — the origin
        // pin (index 0, alongM ≈ 0) must not inflate the goal index.
        val reached = waypointAlongM.drop(1).count { it <= hit.alongM + arrivalRadiusM }

        _navState.value = _navState.value.copy(
            waiting = false,
            arrived = false,
            mode = NavMode.TURN_BY_TURN,
            arrow = next?.type?.toArrow() ?: ArrowDir.STRAIGHT,
            primaryText = next?.let { turnText(it.type) }
                ?: context.getString(R.string.nav_continue),
            distanceText = NavFormat.distance(context, distToTurn, imperial),
            nextStreet = next?.streetName ?: "",
            offRoute = _navState.value.offRoute,
            goalIndex = (reached + 1).coerceIn(1, route.waypoints.size),
            goalCount = route.waypoints.size
        )
    }

    /** Speaks the prepare ("in X, turn left") then execute ("turn left now") cues. */
    private fun announceManeuver(index: Int, maneuver: Maneuver, distToTurn: Double) {
        if (maneuver.type == TurnType.DEPART) return
        if (!voiceEnabled) return
        if (distToTurn <= PREPARE_DIST_M && preparedManeuver != index) {
            preparedManeuver = index
            voiceService.announceEvent(
                context.getString(
                    R.string.voice_nav_prepare,
                    NavFormat.spokenDistance(context, distToTurn, imperial),
                    turnText(maneuver.type)
                )
            )
        }
        if (distToTurn <= EXECUTE_DIST_M && executedManeuver != index) {
            executedManeuver = index
            voiceService.announceEvent(
                context.getString(R.string.voice_nav_now, turnText(maneuver.type))
            )
        }
    }

    private fun handleOffRoute(route: NavRoute, point: GeoPoint, off: Boolean, now: Long) {
        if (!off) {
            // Hysteresis: a single in-tolerance fix on a noisy signal must not
            // reset the re-route timer. Require a sustained on-route window
            // before declaring the rider back on track.
            if (offRouteSinceMs == 0L) return
            if (backOnRouteSinceMs == 0L) backOnRouteSinceMs = now
            if (now - backOnRouteSinceMs >= OFF_ROUTE_GRACE_MS) {
                offRouteSinceMs = 0L
                backOnRouteSinceMs = 0L
                if (_navState.value.offRoute) {
                    _navState.value = _navState.value.copy(offRoute = false)
                }
            }
            return
        }
        backOnRouteSinceMs = 0L
        if (offRouteSinceMs == 0L) offRouteSinceMs = now
        val offFor = now - offRouteSinceMs
        if (offFor < OFF_ROUTE_GRACE_MS) return

        if (!_navState.value.offRoute) _navState.value = _navState.value.copy(offRoute = true)
        if (voiceEnabled && now - lastWrongWayMs > WRONG_WAY_COOLDOWN_MS) {
            lastWrongWayMs = now
            voiceService.announceEvent(context.getString(R.string.voice_nav_wrong_way))
        }
        if (offFor > REROUTE_AFTER_MS && !rerouteInFlight &&
            route.travelMode != TravelMode.STRAIGHT
        ) {
            reroute(route, point)
        }
    }

    /** Re-routes from the current position through the not-yet-reached waypoints. */
    private fun reroute(route: NavRoute, from: GeoPoint) {
        rerouteInFlight = true
        rerouteJob = scope.launch {
            try {
                val reached = GeoMath.nearestOnPolyline(from, route.geometry)?.alongM ?: 0.0
                val remaining = route.waypoints.filterIndexed { i, _ ->
                    (waypointAlongM.getOrNull(i) ?: Double.MAX_VALUE) > reached
                }
                val stops = listOf(Waypoint(from.lat, from.lng)) + remaining
                if (stops.size >= 2) {
                    val fresh = routingService.route(route.name, stops, route.travelMode, routerUrl)
                    // The session may have been stopped or restarted while the
                    // network request was in flight — only adopt the result if
                    // we are still navigating the very same route.
                    if (fresh != null && activeRoute === route) {
                        activeRoute = fresh
                        waypointAlongM = fresh.waypoints.mapNotNull {
                            GeoMath.nearestOnPolyline(it.point(), fresh.geometry)?.alongM
                        }
                        preparedManeuver = -1
                        executedManeuver = -1
                        offRouteSinceMs = 0L
                        backOnRouteSinceMs = 0L
                        Log.i(TAG, "Re-routed from current position")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reroute failed: ${e.message}")
            } finally {
                rerouteInFlight = false
            }
        }
    }

    /** Degraded guidance for routes without maneuvers: home toward the last waypoint. */
    private fun guideHoming(route: NavRoute, point: GeoPoint, heading: Double) {
        val target = route.waypoints.lastOrNull()?.point() ?: return
        val dist = GeoMath.distanceM(point, target)
        if (!arrivalHandled && dist <= arrivalRadiusM) {
            handleArrival()
            return
        }
        val rel = GeoMath.relativeBearing(heading, GeoMath.bearingDeg(point, target))
        _navState.value = _navState.value.copy(
            waiting = false,
            arrived = false,
            mode = NavMode.TURN_BY_TURN,
            arrow = GeoMath.arrowFor(rel),
            relativeBearingDeg = rel.toFloat(),
            primaryText = context.getString(R.string.nav_continue),
            distanceText = NavFormat.distance(context, dist, imperial),
            nextStreet = "",
            goalIndex = route.waypoints.size,
            goalCount = route.waypoints.size
        )
    }

    // --- treasure hunt -----------------------------------------------------------

    private fun computeTreasureHunt(route: NavRoute, point: GeoPoint, heading: Double, now: Long) {
        val goals = route.waypoints
        if (currentGoal >= goals.size) {
            if (!arrivalHandled) handleArrival()
            return
        }
        var target = goals[currentGoal]
        var dist = GeoMath.distanceM(point, target.point())

        if (dist <= arrivalRadiusM) {
            // Reached this goal — advance.
            currentGoal++
            lastGoalDistM = Double.NaN
            lastProximity = null
            if (currentGoal >= goals.size) {
                handleArrival()
                return
            }
            if (voiceEnabled) {
                voiceService.announceEvent(context.getString(R.string.voice_nav_goal_reached))
            }
            target = goals[currentGoal]
            dist = GeoMath.distanceM(point, target.point())
            lastHuntVoiceMs = 0L // force a fresh announcement for the new goal
        }

        val rel = GeoMath.relativeBearing(heading, GeoMath.bearingDeg(point, target.point()))
        val proximity = when {
            dist < arrivalRadiusM * 2.5 -> Proximity.HOT
            !lastGoalDistM.isNaN() && dist < lastGoalDistM - PROX_BAND_M -> Proximity.WARM
            !lastGoalDistM.isNaN() && dist > lastGoalDistM + PROX_BAND_M -> Proximity.COLD
            else -> lastProximity ?: Proximity.WARM
        }

        _navState.value = _navState.value.copy(
            waiting = false,
            arrived = false,
            mode = NavMode.TREASURE_HUNT,
            arrow = GeoMath.arrowFor(rel),
            relativeBearingDeg = rel.toFloat(),
            primaryText = context.getString(
                R.string.nav_hunt_primary, currentGoal + 1, directionText(rel)
            ),
            distanceText = NavFormat.distance(context, dist, imperial),
            nextStreet = "",
            proximity = proximity,
            goalIndex = currentGoal,
            goalCount = goals.size
        )

        // Voice cadence: speak on a fresh goal or every HUNT_VOICE_INTERVAL while moving.
        if (voiceEnabled && now - lastHuntVoiceMs > HUNT_VOICE_INTERVAL_MS) {
            lastHuntVoiceMs = now
            speakHunt(currentGoal + 1, dist, rel, proximity)
        }

        lastGoalDistM = dist
        lastProximity = proximity
    }

    private fun speakHunt(goalNumber: Int, dist: Double, rel: Double, proximity: Proximity) {
        val base = context.getString(
            R.string.voice_nav_hunt,
            goalNumber,
            NavFormat.spokenDistance(context, dist, imperial),
            directionText(rel)
        )
        val proxPhrase = when (proximity) {
            Proximity.HOT -> context.getString(R.string.voice_prox_hot)
            Proximity.WARM -> context.getString(R.string.voice_prox_warmer)
            Proximity.COLD -> context.getString(R.string.voice_prox_colder)
        }
        voiceService.announceEvent(context.getString(R.string.voice_nav_hunt_prox, base, proxPhrase))
    }

    // --- arrival -----------------------------------------------------------------

    private fun handleArrival() {
        if (arrivalHandled) return
        arrivalHandled = true
        _navState.value = _navState.value.copy(
            waiting = false,
            arrived = true,
            offRoute = false,
            arrow = ArrowDir.STRAIGHT,
            primaryText = context.getString(R.string.nav_arrived),
            distanceText = ""
        )
        if (voiceEnabled) {
            voiceService.announceEvent(context.getString(R.string.voice_nav_arrived))
        }
        // Leave the "arrived" banner up briefly, then clear the popup. The job
        // is tracked so a stop()/start() within the window cancels it — it must
        // not tear down a navigation session that was meanwhile restarted.
        arrivalJob = scope.launch {
            delay(ARRIVAL_DISMISS_MS)
            if (arrivalHandled) stop()
        }
    }

    // --- text helpers ------------------------------------------------------------

    private fun turnText(type: TurnType): String = context.getString(
        when (type) {
            TurnType.LEFT -> R.string.nav_turn_left
            TurnType.RIGHT -> R.string.nav_turn_right
            TurnType.SLIGHT_LEFT -> R.string.nav_turn_slight_left
            TurnType.SLIGHT_RIGHT -> R.string.nav_turn_slight_right
            TurnType.SHARP_LEFT -> R.string.nav_turn_sharp_left
            TurnType.SHARP_RIGHT -> R.string.nav_turn_sharp_right
            TurnType.UTURN -> R.string.nav_uturn
            TurnType.DEPART -> R.string.nav_depart
            TurnType.ARRIVE -> R.string.nav_arrived
            TurnType.CONTINUE, TurnType.ROUNDABOUT -> R.string.nav_continue
        }
    )

    /** Heading-relative direction word for Treasure Hunt ("on your left", etc.). */
    private fun directionText(relBearing: Double): String {
        val a = abs(relBearing)
        return context.getString(
            when {
                a <= 25 -> R.string.nav_dir_ahead
                a >= 155 -> R.string.nav_dir_behind
                relBearing > 0 -> if (a <= 65) R.string.nav_dir_slight_right else R.string.nav_dir_right
                else -> if (a <= 65) R.string.nav_dir_slight_left else R.string.nav_dir_left
            }
        )
    }
}
