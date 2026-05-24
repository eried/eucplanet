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
 *    maneuver and flagging off-route / re-routing when the rider drifts off.
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

        // Off-route handling — deliberately unhurried so a brief GPS wobble or
        // a cut corner does not nag the rider.
        private const val OFF_ROUTE_GRACE_MS = 8_000L
        private const val OFF_ROUTE_VOICE_AFTER_MS = 14_000L
        private const val OFF_ROUTE_VOICE_COOLDOWN_MS = 35_000L
        private const val REROUTE_AFTER_MS = 22_000L

        // How long the "arrived" banner lingers before the popup self-clears.
        private const val ARRIVAL_DISMISS_MS = 9_000L

        // Treasure Hunt voice cadence + proximity hysteresis.
        private const val HUNT_VOICE_INTERVAL_MS = 45_000L
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

    init {
        // Push the current navigation cue (or null) to VoiceService whenever
        // it changes. The Report-status announcement reads it for the
        // "Navigation" row, so a Periodic / Trigger report includes the live
        // turn instruction without any caller having to thread it through.
        scope.launch {
            _navState.collect { s ->
                voiceService.currentNavCue =
                    if (s.active && !s.waiting && !s.arrived && s.primaryText.isNotBlank())
                        listOf(s.primaryText, s.distanceText)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    else null
            }
        }
        // Live navigation parameters: arrival radius, off-route tolerance,
        // voice toggle and imperial units. Used to snapshot only at start();
        // now they re-flow whenever the rider edits a setting mid-trip, so
        // the Navigation Settings shortcut in the builder menu is meaningful
        // even with guidance running. Per-waypoint radii (Waypoint.radiusM)
        // still override the global. The engine reads the cached fields on
        // every fix, so updates take effect on the next GPS update.
        scope.launch {
            settingsRepository.settings.collect { s ->
                imperial = s.imperialUnits
                voiceEnabled = s.navVoiceEnabled
                arrivalRadiusM = s.navArrivalRadiusM.toDouble()
                offRouteToleranceM = s.navOffRouteToleranceM.toDouble()
            }
        }
    }

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
    private var lastOffRouteVoiceMs = 0L
    // Tracks whether we already spoke the distance-only "pre-move" cue for
    // the current navigation session (or current goal). Cleared on stop()
    // and whenever the active goal advances. Without this gate the rider
    // would hear "Goal 1 is 300 metres away" every time onFix() ran while
    // standing still.
    private var preMoveCueSpoken = false
    private var rerouteInFlight = false
    private var waypointAlongM: List<Double> = emptyList()

    // --- treasure-hunt state ---
    private var currentGoal = 0
    private var lastGoalDistM = Double.NaN
    private var lastProximity: Proximity? = null
    private var lastHuntVoiceMs = 0L
    // Distance to goal the last time we spoke a warmer/colder/hotter cue.
    // Lets us suppress the next cue when the rider has barely moved -- GPS
    // noise alone would otherwise cycle warm/cold while they stand still.
    private var lastVoicedGoalDistM = Double.NaN
    // Unbroken run of "getting colder" voice cues — escalates to a wrong-way
    // shout; any "warmer" / "hot" cue resets it back to zero.
    private var coldStreak = 0

    private var arrivalHandled = false
    /** Whether the one-shot "already inside the first stop's radius" check has
     *  fired. Limited to the very first fix after Start so that a noisy fix
     *  cannot consume multiple stops at once. */
    private var firstFixAdvanceAttempted = false
    // Destinations already visited and mirrored to the builder's saved route.
    private var lastSyncedReached = 0

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
                goalCount = (route.waypoints.size - 1).coerceAtLeast(1)
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

    /**
     * Mid-trip leg swap. Replaces the active route with [newRoute] without
     * stopping GPS / the foreground service or announcing arrival -- used by
     * the multi-stop nav flow where each leg is solved independently and the
     * engine moves on to the next leg the moment the rider reaches a stop.
     * The route's goal is whatever destination is at index 1; same as the
     * leg used by [start].
     */
    fun advanceLeg(newRoute: NavRoute) {
        if (!isActive) return
        scope.launch {
            activeRoute = newRoute
            resetRuntimeState()
            waypointAlongM = if (newRoute.geometry.size >= 2) {
                newRoute.waypoints.mapNotNull {
                    GeoMath.nearestOnPolyline(it.point(), newRoute.geometry)?.alongM
                }
            } else emptyList()
            // Keep active = true so the rider's UI doesn't blink between
            // "navigating" and "done"; just refresh the goal counters and
            // drop back to waiting/start-riding until the first fix on the
            // new leg arrives.
            _navState.value = _navState.value.copy(
                waiting = true,
                arrived = false,
                offRoute = false,
                primaryText = context.getString(R.string.nav_start_riding),
                distanceText = "",
                goalIndex = 1,
                goalCount = (newRoute.waypoints.size - 1).coerceAtLeast(1)
            )
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

    /** Mirrors whether the centred popup is on screen, for the watch bridge. */
    fun setCueVisible(visible: Boolean) {
        _navState.value = _navState.value.copy(cueVisible = visible)
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
        lastOffRouteVoiceMs = 0L
        rerouteInFlight = false
        // 1, not 0 — waypoint 0 is the rider's start point, never a goal.
        currentGoal = 1
        firstFixAdvanceAttempted = false
        lastGoalDistM = Double.NaN
        lastProximity = null
        lastVoicedGoalDistM = Double.NaN
        lastHuntVoiceMs = 0L
        coldStreak = 0
        arrivalHandled = false
        lastSyncedReached = 0
        preMoveCueSpoken = false
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
        Log.i(
            TAG,
            "fix ${"%.5f".format(point.lat)},${"%.5f".format(point.lng)} " +
                "moving=$moving heading=${heading?.toInt()} mode=$navMode"
        )

        val h = heading
        if (h == null) {
            // No travel direction yet — we cannot say "ahead / left / right"
            // because we don't know which way the rider is FACING. But we DO
            // know where the next goal is and how far away — so the popup
            // shows the distance, and a one-shot cue speaks it once on the
            // first fix.
            //
            // If the rider pressed Start while ALREADY inside the FIRST
            // stop's arrival radius, advance past it so they don't sit on
            // "Start riding" forever next to a stop that is already done.
            // We only ever do this for the FIRST stop (currentGoal == 1
            // means we are still on the first unvisited destination) and
            // we only check that ONE stop -- not a chain -- so a noisy
            // first fix that happens to land inside several radii at once
            // doesn't sweep the whole route away.
            if (!firstFixAdvanceAttempted && currentGoal == 1 &&
                route.waypoints.size > 1
            ) {
                firstFixAdvanceAttempted = true
                val g = route.waypoints[1]
                val d = GeoMath.distanceM(point, g.point())
                if (d <= (g.radiusM ?: arrivalRadiusM)) {
                    currentGoal = 2
                    lastGoalDistM = Double.NaN
                    lastProximity = null
                    lastVoicedGoalDistM = Double.NaN
                    preMoveCueSpoken = false
                    if (currentGoal >= route.waypoints.size) {
                        if (!arrivalHandled) handleArrival()
                        return
                    }
                }
            }
            val goal = route.waypoints.getOrNull(currentGoal)
            val distToGoal = if (goal != null) {
                GeoMath.distanceM(point, goal.point())
            } else Double.NaN
            val distanceText = if (!distToGoal.isNaN()) {
                NavFormat.distance(context, distToGoal, imperial)
            } else ""
            // Mirror currentGoal into navState so the VM trim drops stops
            // we already advanced past in the loop above.
            val goalCount = (route.waypoints.size - 1).coerceAtLeast(1)
            _navState.value = _navState.value.copy(
                waiting = true,
                arrow = ArrowDir.STRAIGHT,
                primaryText = context.getString(R.string.nav_start_riding),
                distanceText = distanceText,
                goalIndex = currentGoal.coerceIn(1, goalCount),
                goalCount = goalCount
            )
            syncBuilderRoute((currentGoal - 1).coerceAtLeast(0))
            if (voiceEnabled && !preMoveCueSpoken && !distToGoal.isNaN()) {
                preMoveCueSpoken = true
                voiceService.announceEvent(
                    context.getString(
                        R.string.voice_nav_hunt_distance,
                        goalLabel(currentGoal),
                        NavFormat.spokenDistance(context, distToGoal, imperial)
                    )
                )
            }
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
        val finalRadius = route.waypoints.lastOrNull()?.radiusM ?: arrivalRadiusM
        Log.i(
            TAG,
            "TBT offBy=${"%.0f".format(hit.distanceM)}m along=${"%.0f".format(hit.alongM)}" +
                " toEnd=${"%.0f".format(distToEnd)}m tol=${offRouteToleranceM.toInt()}" +
                " offRoute=${_navState.value.offRoute}"
        )

        if (!arrivalHandled && distToEnd <= finalRadius) {
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
        syncBuilderRoute(reached)

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
            goalIndex = (reached + 1).coerceIn(1, (route.waypoints.size - 1).coerceAtLeast(1)),
            goalCount = (route.waypoints.size - 1).coerceAtLeast(1)
        )
    }

    /** Speaks the prepare ("in X, turn left") then execute ("turn left now") cues. */
    private fun announceManeuver(index: Int, maneuver: Maneuver, distToTurn: Double) {
        // DEPART has no cue; ARRIVE is left to handleArrival() — announcing the
        // final maneuver here too made arrival speak two or three times over.
        if (maneuver.type == TurnType.DEPART || maneuver.type == TurnType.ARRIVE) return
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

        if (!_navState.value.offRoute) {
            _navState.value = _navState.value.copy(offRoute = true)
            Log.i(TAG, "off-route declared (offFor=${offFor}ms)")
        }
        // The spoken off-route cue waits well past the visual flag, so a
        // short detour clears itself before the rider is ever told off.
        if (voiceEnabled && offFor > OFF_ROUTE_VOICE_AFTER_MS &&
            now - lastOffRouteVoiceMs > OFF_ROUTE_VOICE_COOLDOWN_MS
        ) {
            lastOffRouteVoiceMs = now
            voiceService.announceEvent(context.getString(R.string.nav_off_route))
        }
        if (offFor > REROUTE_AFTER_MS && !rerouteInFlight &&
            route.travelMode != TravelMode.STRAIGHT
        ) {
            Log.i(TAG, "triggering reroute (offFor=${offFor}ms)")
            reroute(route, point)
        }
    }

    /** Re-routes from the current position through the not-yet-reached waypoints. */
    private fun reroute(route: NavRoute, from: GeoPoint) {
        rerouteInFlight = true
        if (voiceEnabled) {
            voiceService.announceEvent(context.getString(R.string.nav_recalculating))
        }
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
        val lastWp = route.waypoints.lastOrNull() ?: return
        val target = lastWp.point()
        val dist = GeoMath.distanceM(point, target)
        if (!arrivalHandled && dist <= (lastWp.radiusM ?: arrivalRadiusM)) {
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
            goalIndex = (route.waypoints.size - 1).coerceAtLeast(1),
            goalCount = (route.waypoints.size - 1).coerceAtLeast(1)
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
        Log.i(
            TAG,
            "HUNT goal=$currentGoal/${goals.size - 1} dist=${"%.0f".format(dist)}m" +
                " radius=${(target.radiusM ?: arrivalRadiusM).toInt()}"
        )

        if (dist <= (target.radiusM ?: arrivalRadiusM)) {
            // Reached this goal — advance.
            currentGoal++
            lastGoalDistM = Double.NaN
            lastProximity = null
            lastVoicedGoalDistM = Double.NaN
            // Re-arm the pre-move distance cue for the new goal — if the
            // rider stops between goals (e.g. checkpoint break) and motion
            // resumes pointing toward goal N+1, they hear how far that one
            // is even before a heading is re-established.
            preMoveCueSpoken = false
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
            // Just the direction phrase ("behind you on your right", "ahead",
            // …) -- the popup renders it next to distanceText as a single
            // brief line ("300 m behind you on your right"). The stop label
            // appears in the top bar of the popup, not here, so we don't
            // double it up and force a 2-line wrap.
            primaryText = directionText(rel),
            distanceText = NavFormat.distance(context, dist, imperial),
            nextStreet = "",
            proximity = proximity,
            goalIndex = currentGoal,
            goalCount = (goals.size - 1).coerceAtLeast(1)
        )

        // Voice cadence: speak on a fresh goal or every HUNT_VOICE_INTERVAL
        // while moving -- BUT suppress when the rider has barely moved since
        // the last cue. Without this, a stationary rider hears "colder" /
        // "warmer" every 45 s purely from GPS jitter (~5 m amplitude is
        // routine even with a clear sky). The bar is "substantial movement",
        // defined relative to the goal's arrival radius: half the radius,
        // never less than 20 m so a generous goal doesn't drown out genuinely
        // small but meaningful progress. Once-per-fresh-goal cues bypass the
        // filter (lastVoicedGoalDistM = NaN after the goal advance).
        if (voiceEnabled && now - lastHuntVoiceMs > HUNT_VOICE_INTERVAL_MS) {
            val effectiveRadius = (target.radiusM ?: arrivalRadiusM)
            val noiseThresholdM = maxOf(20.0, effectiveRadius / 2.0)
            val moved = if (lastVoicedGoalDistM.isNaN()) Double.POSITIVE_INFINITY
                else kotlin.math.abs(dist - lastVoicedGoalDistM)
            if (moved >= noiseThresholdM) {
                lastHuntVoiceMs = now
                lastVoicedGoalDistM = dist
                // Count an unbroken run of "colder" cues; warmer / hot resets it.
                coldStreak = if (proximity == Proximity.COLD) coldStreak + 1 else 0
                speakHunt(currentGoal, dist, rel, proximity, coldStreak)
            }
        }

        syncBuilderRoute(currentGoal - 1)
        lastGoalDistM = dist
        lastProximity = proximity
    }

    /**
     * Mirrors navigation progress back to the builder's saved route: drops the
     * stops already visited and clears the geometry, so re-opening the builder
     * mid-trip shows only the stops still ahead and recomputes from where the
     * rider is now. When the last stop is done the route is left empty.
     */
    private fun syncBuilderRoute(reachedDests: Int) {
        if (reachedDests == lastSyncedReached) return
        lastSyncedReached = reachedDests
        val route = activeRoute ?: return
        // route.waypoints is [rider, dest1, dest2, ...] — keep the unvisited.
        val remaining = route.waypoints.drop(1 + reachedDests)
        scope.launch {
            runCatching {
                val s = settingsRepository.get()
                val json = if (remaining.isEmpty()) "" else route.copy(
                    waypoints = remaining,
                    geometry = emptyList(),
                    maneuvers = emptyList()
                ).toJson().toString()
                // Re-stamp the freshness timestamp on every trim so the
                // Builder treats this as a "live" navigation even after
                // hours of riding -- only really old entries (likely
                // Google-Auto-Backup ghosts from a previous install) get
                // gated out on re-open.
                settingsRepository.update(
                    s.copy(
                        navCurrentRouteJson = json,
                        navCurrentRouteSavedAt = if (remaining.isEmpty())
                            0L else System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun speakHunt(
        waypointIndex: Int,
        dist: Double,
        rel: Double,
        proximity: Proximity,
        coldStreak: Int
    ) {
        val base = context.getString(
            R.string.voice_nav_hunt,
            goalLabel(waypointIndex),
            NavFormat.spokenDistance(context, dist, imperial),
            directionText(rel)
        )
        val proxPhrase = when (proximity) {
            Proximity.HOT -> context.getString(R.string.voice_prox_hot)
            Proximity.WARM -> context.getString(R.string.voice_prox_warmer)
            Proximity.COLD -> {
                // Escalating cold ladder: each step in an unbroken cold streak
                // names the drift in slightly stronger language; once the
                // rider has been "freezing cold" for two more cues without
                // warming up, switch to a deadpan "Are we there yet?" that
                // sticks for every cold cue after.
                //   1-2 → "getting colder"
                //     3 → "even colder"
                //     4 → "getting colder"
                //     5 → "freezing cold"
                //     6 → "getting colder"
                //   7+ → "Are we there yet?" (sticks)
                when (coldStreak) {
                    3 -> context.getString(R.string.voice_prox_even_colder)
                    5 -> context.getString(R.string.voice_prox_freezing)
                    in 7..Int.MAX_VALUE -> context.getString(R.string.voice_prox_lost)
                    else -> context.getString(R.string.voice_prox_colder)
                }
            }
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
        // The saved-builder-route clear happens HERE (after the delay), not
        // immediately, so an advanceLeg() call right after an intermediate
        // arrival can cancel it without losing the rider's planned remainder.
        arrivalJob = scope.launch {
            delay(ARRIVAL_DISMISS_MS)
            if (!arrivalHandled) return@launch
            runCatching {
                val s = settingsRepository.get()
                settingsRepository.update(
                    s.copy(navCurrentRouteJson = "", navCurrentRouteSavedAt = 0L)
                )
            }
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

    /** "Next stop" for an intermediate waypoint, "Destination" for the last. */
    private fun goalLabel(waypointIndex: Int): String {
        val last = (activeRoute?.waypoints?.size ?: 0) - 1
        return context.getString(
            if (waypointIndex >= last) R.string.nav_label_destination
            else R.string.nav_label_next_stop
        )
    }

    /**
     * Heading-relative direction word for Treasure Hunt. Buckets the relative
     * bearing into six bands per side, so the back-diagonals ("behind you, on
     * your right") read distinct from sideways or straight behind:
     *
     *     |rel|  0..25   25..65   65..110   110..155   >=155
     *      word  ahead   slight   side      back-diag  behind
     *
     * Sign of rel picks left vs right.
     */
    private fun directionText(relBearing: Double): String {
        val a = abs(relBearing)
        return context.getString(
            when {
                a <= 25 -> R.string.nav_dir_ahead
                a >= 155 -> R.string.nav_dir_behind
                relBearing > 0 -> when {
                    a <= 65 -> R.string.nav_dir_slight_right
                    a <= 110 -> R.string.nav_dir_right
                    else -> R.string.nav_dir_behind_right
                }
                else -> when {
                    a <= 65 -> R.string.nav_dir_slight_left
                    a <= 110 -> R.string.nav_dir_left
                    else -> R.string.nav_dir_behind_left
                }
            }
        )
    }
}
