@file:OptIn(ExperimentalCoroutinesApi::class)

package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.AppSettings
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
import com.eried.eucplanet.location.GpsFix
import com.eried.eucplanet.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The live-guidance brain. Faithful port of Android's NavigationEngine — the
 * logic (heading-from-trace, turn-by-turn, treasure-hunt, off-route/re-route,
 * multi-stop arrival) is unchanged; only the platform seams differ:
 *  - Location/TripRepository → [location] (shared GpsFix flow) + [startLocation]
 *  - WheelRepository → [wheelSpeedKmh]
 *  - SettingsRepository → [settings]
 *  - VoiceService → [speak] + [setNavCue]
 *  - Context/R.string → inlined nav strings
 *  - Executors single-thread dispatcher → Dispatchers.Default.limitedParallelism(1)
 */
class NavigationEngine(
    private val location: StateFlow<GpsFix?>,
    private val wheelSpeedKmh: () -> Float,
    private val settings: StateFlow<AppSettings>,
    private val routingService: RoutingService,
    private val currentRouteStore: CurrentRouteStore,
    private val speak: (String) -> Unit,
    private val setNavCue: (String?) -> Unit,
    private val startLocation: () -> Unit,
    private val nowMs: () -> Long,
) {
    companion object {
        private const val TAG = "NavigationEngine"

        private const val HEADING_WINDOW_MS = 8_000L
        private const val FIX_BUFFER_MS = 14_000L
        private const val MIN_HEADING_DISP_M = 8.0

        private const val MOVING_MS = 1.2
        private const val MOVING_KMH = 4.0

        private const val PREPARE_DIST_M = 200.0
        private const val EXECUTE_DIST_M = 30.0

        private const val OFF_ROUTE_GRACE_MS = 8_000L
        private const val OFF_ROUTE_VOICE_AFTER_MS = 14_000L
        private const val OFF_ROUTE_VOICE_COOLDOWN_MS = 35_000L
        private const val REROUTE_AFTER_MS = 22_000L

        private const val ARRIVAL_DISMISS_MS = 9_000L
        private const val INTERMEDIATE_FLASH_MS = 1_500L

        private const val HUNT_VOICE_INTERVAL_MS = 45_000L
        private const val PROX_BAND_M = 4.0

        private const val MIN_INTER_STOP_MOVE_M = 30.0
    }

    // Serialise every state mutation onto a single logical thread, mirroring the
    // Android engine's single-thread executor so the fix handler, re-routing and
    // start/stop paths never race.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val _navState = MutableStateFlow(NavState())
    val navState: StateFlow<NavState> = _navState.asStateFlow()

    private val _activeLeg = MutableStateFlow<NavRoute?>(null)
    val activeLeg: StateFlow<NavRoute?> = _activeLeg.asStateFlow()

    val isActive: Boolean get() = _navState.value.active

    init {
        // Push the current navigation cue (or null) to the voice layer whenever
        // it changes, so a Report-status announcement can include the live turn.
        scope.launch {
            _navState.collect { s ->
                setNavCue(
                    if (s.active && !s.waiting && !s.arrived && s.primaryText.isNotBlank())
                        listOf(s.primaryText, s.distanceText)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                    else null
                )
            }
        }
        // Live navigation parameters re-flow whenever the rider edits a setting
        // mid-trip; the engine reads the cached fields on every fix.
        scope.launch {
            settings.collect { s ->
                imperial = s.imperial()
                voiceEnabled = s.navVoiceGuidance
                arrivalRadiusM = s.navArrivalRadiusM.toDouble()
                offRouteToleranceM = s.navOffRouteToleranceM.toDouble()
            }
        }
    }

    private var initJob: Job? = null
    private var collectJob: Job? = null
    private var rerouteJob: Job? = null
    private var arrivalJob: Job? = null
    private var activeRoute: NavRoute? = null
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
    private var lastIntermediateAnnounced = 0
    private var offRouteSinceMs = 0L
    private var backOnRouteSinceMs = 0L
    private var lastOffRouteVoiceMs = 0L
    private var preMoveCueSpoken = false
    private var rerouteInFlight = false
    private var waypointAlongM: List<Double> = emptyList()

    // --- treasure-hunt state ---
    private var currentGoal = 0
    private var lastGoalDistM = Double.NaN
    private var lastProximity: Proximity? = null
    private var lastHuntVoiceMs = 0L
    private var lastVoicedGoalDistM = Double.NaN
    private var coldStreak = 0

    private var arrivalHandled = false
    private var lastArrivalPoint: GeoPoint? = null
    private var lastSyncedReached = 0

    /** Begins guidance. Must be called while the app is in the foreground. */
    fun start(route: NavRoute, mode: NavMode) {
        if (route.waypoints.size < 2 && route.geometry.size < 2) {
            Log.w(TAG, "start() ignored, route has nothing to navigate")
            return
        }
        startLocation()
        stop()
        initJob = scope.launch {
            val s = settings.value
            imperial = s.imperial()
            voiceEnabled = s.navVoiceGuidance
            arrivalRadiusM = s.navArrivalRadiusM.toDouble()
            offRouteToleranceM = s.navOffRouteToleranceM.toDouble()
            routerUrl = RoutingService.effectiveRouterUrl(s.navRouterUrl)

            activeRoute = route
            _activeLeg.value = route
            navMode = mode
            resetRuntimeState()
            lastArrivalPoint = null
            waypointAlongM = if (route.geometry.size >= 2) {
                route.waypoints.mapNotNull {
                    GeoMath.nearestOnPolyline(it.point(), route.geometry)?.alongM
                }
            } else emptyList()

            _navState.value = NavState(
                active = true,
                mode = mode,
                waiting = true,
                primaryText = NAV_START_RIDING,
                goalIndex = 1,
                goalCount = (route.waypoints.size - 1).coerceAtLeast(1),
            )

            if (voiceEnabled) speak(NAV_START_RIDING)

            collectJob = scope.launch {
                location.collect { loc -> if (loc != null) onFix(loc) }
            }
            Log.i(TAG, "Navigation started, mode=$mode, ${route.waypoints.size} stops")
        }
    }

    /** Mid-trip leg swap. Replaces the active route without stopping GPS. */
    fun advanceLeg(newRoute: NavRoute) {
        if (!isActive) return
        scope.launch {
            activeRoute = newRoute
            _activeLeg.value = newRoute
            resetRuntimeState()
            waypointAlongM = if (newRoute.geometry.size >= 2) {
                newRoute.waypoints.mapNotNull {
                    GeoMath.nearestOnPolyline(it.point(), newRoute.geometry)?.alongM
                }
            } else emptyList()
            val loc = location.value
            val goal = newRoute.waypoints.getOrNull(1)?.point()
            val distanceText = if (loc != null && goal != null) {
                NavFormat.distance(GeoMath.distanceM(GeoPoint(loc.lat, loc.lng), goal), imperial)
            } else ""
            _navState.value = _navState.value.copy(
                waiting = true,
                arrived = false,
                offRoute = false,
                arrow = ArrowDir.STRAIGHT,
                primaryText = NAV_START_RIDING,
                distanceText = distanceText,
                nextStreet = "",
                proximity = null,
                goalIndex = 1,
                goalCount = (newRoute.waypoints.size - 1).coerceAtLeast(1),
                popupTick = _navState.value.popupTick + 1,
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
        _activeLeg.value = null
        _navState.value = _navState.value.copy(active = false)
        scope.launch { _navState.value = _navState.value.copy(active = false) }
        Log.i(TAG, "Navigation stopped")
    }

    fun setMinimized(minimized: Boolean) {
        _navState.value = _navState.value.copy(minimized = minimized)
    }

    /** Mirrors whether the centred popup is on screen, for the watch bridge. */
    fun setCueVisible(visible: Boolean) {
        _navState.value = _navState.value.copy(cueVisible = visible)
    }

    /** Re-opens the centred popup (used by the dashboard navigator button). */
    fun requestPopup() {
        _navState.value = _navState.value.copy(
            minimized = false,
            popupTick = _navState.value.popupTick + 1,
        )
    }

    private fun resetRuntimeState() {
        recentFixes.clear()
        heading = null
        preparedManeuver = -1
        executedManeuver = -1
        lastIntermediateAnnounced = 0
        offRouteSinceMs = 0L
        backOnRouteSinceMs = 0L
        lastOffRouteVoiceMs = 0L
        rerouteInFlight = false
        currentGoal = 1
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

    private fun onFix(loc: GpsFix) {
        val route = activeRoute ?: return
        if (arrivalHandled) return
        val now = nowMs()
        val point = GeoPoint(loc.lat, loc.lng)

        val gpsSpeed = (if (loc.speedKmh >= 0f) loc.speedKmh else 0f) / 3.6 // m/s (negative = unknown)
        val wheelKmh = abs(wheelSpeedKmh()).toDouble()
        val moving = gpsSpeed > MOVING_MS || wheelKmh > MOVING_KMH

        recentFixes.addLast(Fix(point, now, moving))
        while (recentFixes.isNotEmpty() && now - recentFixes.first().timeMs > FIX_BUFFER_MS) {
            recentFixes.removeFirst()
        }
        updateHeading(now)

        val h = heading
        if (h == null) {
            val armed = lastArrivalPoint?.let { last ->
                GeoMath.distanceM(point, last) > MIN_INTER_STOP_MOVE_M
            } ?: true
            if (armed && currentGoal == 1 && route.waypoints.size > 1) {
                val g = route.waypoints[1]
                val d = GeoMath.distanceM(point, g.point())
                val r = (g.radiusM ?: arrivalRadiusM)
                if (d <= r) {
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
            val distToGoal = if (goal != null) GeoMath.distanceM(point, goal.point()) else Double.NaN
            val distanceText = if (!distToGoal.isNaN()) NavFormat.distance(distToGoal, imperial) else ""
            val goalCount = (route.waypoints.size - 1).coerceAtLeast(1)
            _navState.value = _navState.value.copy(
                waiting = true,
                arrow = ArrowDir.STRAIGHT,
                primaryText = NAV_START_RIDING,
                distanceText = distanceText,
                goalIndex = currentGoal.coerceIn(1, goalCount),
                goalCount = goalCount,
            )
            syncBuilderRoute((currentGoal - 1).coerceAtLeast(0))
            if (voiceEnabled && !preMoveCueSpoken && !distToGoal.isNaN()) {
                preMoveCueSpoken = true
                speak(voiceHuntDistance(goalLabel(currentGoal), NavFormat.spokenDistance(distToGoal, imperial)))
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
        if (route.maneuvers.isEmpty() || route.geometry.size < 2) {
            guideHoming(route, point, heading)
            return
        }

        val hit = GeoMath.nearestOnPolyline(point, route.geometry) ?: return
        val distToEnd = (route.totalDistanceM - hit.alongM).coerceAtLeast(0.0)
        val finalRadiusFromWp = route.waypoints.lastOrNull()?.radiusM
        val finalRadius = finalRadiusFromWp ?: arrivalRadiusM

        val lastWp = route.waypoints.lastOrNull()
        val directDistToEnd = lastWp?.let { GeoMath.distanceM(point, it.point()) } ?: Double.MAX_VALUE
        if (!arrivalHandled && (distToEnd <= finalRadius || directDistToEnd <= finalRadius)) {
            handleArrival()
            return
        }

        handleOffRoute(route, point, hit.distanceM > offRouteToleranceM, now)

        val nextIndex = route.maneuvers.indexOfFirst { it.distanceFromStartM > hit.alongM + 5.0 }
        val next: Maneuver? = route.maneuvers.getOrNull(nextIndex)
        val distToTurn = ((next?.distanceFromStartM ?: route.totalDistanceM) - hit.alongM)
            .coerceAtLeast(0.0)

        if (next != null) announceManeuver(nextIndex, next, distToTurn)

        val reached = waypointAlongM.drop(1).count { it <= hit.alongM + arrivalRadiusM }
        syncBuilderRoute(reached)

        if (reached > lastIntermediateAnnounced && reached < route.waypoints.size - 1) {
            lastIntermediateAnnounced = reached
            if (voiceEnabled) speak(VOICE_GOAL_REACHED)
        }

        _navState.value = _navState.value.copy(
            waiting = false,
            arrived = false,
            mode = NavMode.TURN_BY_TURN,
            arrow = next?.type?.toArrow() ?: ArrowDir.STRAIGHT,
            primaryText = next?.let { turnText(it.type) } ?: NAV_CONTINUE,
            distanceText = NavFormat.distance(distToTurn, imperial),
            nextStreet = next?.streetName ?: "",
            offRoute = _navState.value.offRoute,
            goalIndex = (reached + 1).coerceIn(1, (route.waypoints.size - 1).coerceAtLeast(1)),
            goalCount = (route.waypoints.size - 1).coerceAtLeast(1),
        )
    }

    /** Speaks the prepare ("in X, turn left") then execute ("turn left now") cues. */
    private fun announceManeuver(index: Int, maneuver: Maneuver, distToTurn: Double) {
        if (maneuver.type == TurnType.DEPART || maneuver.type == TurnType.ARRIVE) return
        if (!voiceEnabled) return
        if (distToTurn <= PREPARE_DIST_M && preparedManeuver != index) {
            preparedManeuver = index
            speak(voicePrepare(NavFormat.spokenDistance(distToTurn, imperial), turnText(maneuver.type)))
        }
        if (distToTurn <= EXECUTE_DIST_M && executedManeuver != index) {
            executedManeuver = index
            speak(voiceNow(turnText(maneuver.type)))
        }
    }

    private fun handleOffRoute(route: NavRoute, point: GeoPoint, off: Boolean, now: Long) {
        if (!off) {
            if (offRouteSinceMs == 0L) return
            if (backOnRouteSinceMs == 0L) backOnRouteSinceMs = now
            if (now - backOnRouteSinceMs >= OFF_ROUTE_GRACE_MS) {
                offRouteSinceMs = 0L
                backOnRouteSinceMs = 0L
                if (_navState.value.offRoute) _navState.value = _navState.value.copy(offRoute = false)
            }
            return
        }
        backOnRouteSinceMs = 0L
        if (offRouteSinceMs == 0L) offRouteSinceMs = now
        val offFor = now - offRouteSinceMs
        if (offFor < OFF_ROUTE_GRACE_MS) return

        if (!_navState.value.offRoute) {
            _navState.value = _navState.value.copy(offRoute = true)
        }
        if (voiceEnabled && offFor > OFF_ROUTE_VOICE_AFTER_MS &&
            now - lastOffRouteVoiceMs > OFF_ROUTE_VOICE_COOLDOWN_MS
        ) {
            lastOffRouteVoiceMs = now
            speak(NAV_OFF_ROUTE)
        }
        if (offFor > REROUTE_AFTER_MS && !rerouteInFlight && route.travelMode != TravelMode.STRAIGHT) {
            reroute(route, point)
        }
    }

    /** Re-routes from the current position through the not-yet-reached waypoints. */
    private fun reroute(route: NavRoute, from: GeoPoint) {
        rerouteInFlight = true
        if (voiceEnabled) speak(NAV_RECALCULATING)
        rerouteJob = scope.launch {
            try {
                val reached = GeoMath.nearestOnPolyline(from, route.geometry)?.alongM ?: 0.0
                val remaining = route.waypoints.filterIndexed { i, _ ->
                    (waypointAlongM.getOrNull(i) ?: Double.MAX_VALUE) > reached
                }
                val stops = listOf(Waypoint(from.lat, from.lng)) + remaining
                if (stops.size >= 2) {
                    val fresh = routingService.route(route.name, stops, route.travelMode, routerUrl)
                    if (fresh != null && activeRoute === route) {
                        activeRoute = fresh
                        _activeLeg.value = fresh
                        waypointAlongM = fresh.waypoints.mapNotNull {
                            GeoMath.nearestOnPolyline(it.point(), fresh.geometry)?.alongM
                        }
                        preparedManeuver = -1
                        executedManeuver = -1
                        lastIntermediateAnnounced = 0
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
            primaryText = NAV_CONTINUE,
            distanceText = NavFormat.distance(dist, imperial),
            nextStreet = "",
            goalIndex = (route.waypoints.size - 1).coerceAtLeast(1),
            goalCount = (route.waypoints.size - 1).coerceAtLeast(1),
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

        if (dist <= (target.radiusM ?: arrivalRadiusM)) {
            currentGoal++
            lastGoalDistM = Double.NaN
            lastProximity = null
            lastVoicedGoalDistM = Double.NaN
            preMoveCueSpoken = false
            if (currentGoal >= goals.size) {
                handleArrival()
                return
            }
            if (voiceEnabled) speak(VOICE_GOAL_REACHED)
            target = goals[currentGoal]
            dist = GeoMath.distanceM(point, target.point())
            lastHuntVoiceMs = 0L
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
            primaryText = directionText(rel),
            distanceText = NavFormat.distance(dist, imperial),
            nextStreet = "",
            proximity = proximity,
            goalIndex = currentGoal,
            goalCount = (goals.size - 1).coerceAtLeast(1),
        )

        if (voiceEnabled && now - lastHuntVoiceMs > HUNT_VOICE_INTERVAL_MS) {
            val effectiveRadius = (target.radiusM ?: arrivalRadiusM)
            val noiseThresholdM = maxOf(20.0, effectiveRadius / 2.0)
            val moved = if (lastVoicedGoalDistM.isNaN()) Double.POSITIVE_INFINITY
                else abs(dist - lastVoicedGoalDistM)
            if (moved >= noiseThresholdM) {
                lastHuntVoiceMs = now
                lastVoicedGoalDistM = dist
                coldStreak = if (proximity == Proximity.COLD) coldStreak + 1 else 0
                speakHunt(currentGoal, dist, rel, proximity, coldStreak)
            }
        }

        syncBuilderRoute(currentGoal - 1)
        lastGoalDistM = dist
        lastProximity = proximity
    }

    /** Mirrors navigation progress back to the in-memory current route. */
    private fun syncBuilderRoute(reachedDests: Int) {
        if (reachedDests == lastSyncedReached) return
        lastSyncedReached = reachedDests
        val route = activeRoute ?: return
        val remaining = route.waypoints.drop(1 + reachedDests)
        currentRouteStore.set(
            if (remaining.isEmpty()) null
            else route.copy(
                waypoints = remaining,
                geometry = emptyList(),
                maneuvers = emptyList(),
                totalDistanceM = 0.0,
            )
        )
    }

    private fun speakHunt(waypointIndex: Int, dist: Double, rel: Double, proximity: Proximity, coldStreak: Int) {
        val base = voiceHunt(goalLabel(waypointIndex), NavFormat.spokenDistance(dist, imperial), directionText(rel))
        val proxPhrase = when (proximity) {
            Proximity.HOT -> VOICE_PROX_HOT
            Proximity.WARM -> VOICE_PROX_WARMER
            Proximity.COLD -> when (coldStreak) {
                3 -> VOICE_PROX_EVEN_COLDER
                5 -> VOICE_PROX_FREEZING
                in 7..Int.MAX_VALUE -> VOICE_PROX_LOST
                else -> VOICE_PROX_COLDER
            }
        }
        speak(voiceHuntProx(base, proxPhrase))
    }

    // --- arrival -----------------------------------------------------------------

    private fun handleArrival() {
        if (arrivalHandled) return
        arrivalHandled = true
        val justArrivedGoal = activeRoute?.waypoints?.lastOrNull()?.point()
        lastArrivalPoint = justArrivedGoal
        scope.launch {
            val info = markPassedAndFindNext(justArrivedGoal)
            if (info != null && info.nextStop != null) {
                val nextLeg = buildNextLeg(info.nextStop, info.routeForLeg)
                if (nextLeg != null) _activeLeg.value = nextLeg
                _navState.value = _navState.value.copy(
                    waiting = false,
                    arrived = true,
                    offRoute = false,
                    minimized = false,
                    arrow = ArrowDir.STRAIGHT,
                    primaryText = navArrivedGoal(info.justArrivedIndex),
                    distanceText = "",
                    nextStreet = "",
                    proximity = null,
                    goalIndex = info.justArrivedIndex,
                    goalCount = info.totalGoals,
                    popupTick = _navState.value.popupTick + 1,
                )
                if (voiceEnabled) speak(VOICE_GOAL_REACHED)
                delay(INTERMEDIATE_FLASH_MS)
                if (nextLeg != null) advanceLeg(nextLeg) else { Log.i(TAG, "no leg built -- stopping"); stop() }
                return@launch
            }
            _navState.value = _navState.value.copy(
                waiting = false,
                arrived = true,
                offRoute = false,
                minimized = false,
                arrow = ArrowDir.STRAIGHT,
                primaryText = NAV_ARRIVED,
                distanceText = "",
                nextStreet = "",
                proximity = null,
                popupTick = _navState.value.popupTick + 1,
            )
            if (voiceEnabled) speak(VOICE_ARRIVED)
            arrivalJob = scope.launch {
                delay(ARRIVAL_DISMISS_MS)
                if (!arrivalHandled) return@launch
                stop()
            }
        }
    }

    private data class ArrivalInfo(
        val nextStop: Waypoint?,
        val routeForLeg: NavRoute,
        val justArrivedIndex: Int,
        val totalGoals: Int,
    )

    private suspend fun markPassedAndFindNext(justArrivedGoal: GeoPoint?): ArrivalInfo? {
        if (justArrivedGoal == null) return null
        return runCatching {
            val existing = currentRouteStore.get() ?: return@runCatching null
            var justArrivedIndex = -1
            val updatedWps = existing.waypoints.mapIndexed { idx, w ->
                val match = !w.passed &&
                    abs(w.lat - justArrivedGoal.lat) < 1e-6 &&
                    abs(w.lng - justArrivedGoal.lng) < 1e-6
                if (match) { justArrivedIndex = idx + 1; w.copy(passed = true) } else w
            }
            if (justArrivedIndex > 0) {
                currentRouteStore.set(
                    existing.copy(
                        waypoints = updatedWps,
                        geometry = emptyList(),
                        maneuvers = emptyList(),
                        totalDistanceM = 0.0,
                    )
                )
            }
            val updatedRoute = existing.copy(waypoints = updatedWps)
            val nextNonPassed = updatedWps.firstOrNull { !it.passed }
            ArrivalInfo(
                nextStop = nextNonPassed,
                routeForLeg = updatedRoute,
                justArrivedIndex = justArrivedIndex.coerceAtLeast(1),
                totalGoals = updatedWps.size,
            )
        }.getOrNull()
    }

    private suspend fun buildNextLeg(nextStop: Waypoint, route: NavRoute): NavRoute? {
        val loc = location.value ?: return null
        val mode = route.travelMode
        val legWps = listOf(Waypoint(loc.lat, loc.lng), nextStop)
        return if (mode == TravelMode.STRAIGHT) {
            RoutingService.straightLineRoute(route.name, legWps)
        } else {
            routingService.route(route.name, legWps, mode, routerUrl)
                ?: RoutingService.straightLineRoute(route.name, legWps)
        }
    }

    // --- text helpers (inlined English nav strings) ------------------------------

    private fun turnText(type: TurnType): String = when (type) {
        TurnType.LEFT -> NAV_TURN_LEFT
        TurnType.RIGHT -> NAV_TURN_RIGHT
        TurnType.SLIGHT_LEFT -> NAV_TURN_SLIGHT_LEFT
        TurnType.SLIGHT_RIGHT -> NAV_TURN_SLIGHT_RIGHT
        TurnType.SHARP_LEFT -> NAV_TURN_SHARP_LEFT
        TurnType.SHARP_RIGHT -> NAV_TURN_SHARP_RIGHT
        TurnType.UTURN -> NAV_UTURN
        TurnType.DEPART -> NAV_DEPART
        TurnType.ARRIVE -> NAV_ARRIVED
        TurnType.CONTINUE, TurnType.ROUNDABOUT -> NAV_CONTINUE
    }

    private fun goalLabel(waypointIndex: Int): String {
        val last = (activeRoute?.waypoints?.size ?: 0) - 1
        return if (waypointIndex >= last) NAV_LABEL_DESTINATION else NAV_LABEL_NEXT_STOP
    }

    private fun directionText(relBearing: Double): String {
        val a = abs(relBearing)
        return when {
            a <= 25 -> NAV_DIR_AHEAD
            a >= 155 -> NAV_DIR_BEHIND
            relBearing > 0 -> when {
                a <= 65 -> NAV_DIR_SLIGHT_RIGHT
                a <= 110 -> NAV_DIR_RIGHT
                else -> NAV_DIR_BEHIND_RIGHT
            }
            else -> when {
                a <= 65 -> NAV_DIR_SLIGHT_LEFT
                a <= 110 -> NAV_DIR_LEFT
                else -> NAV_DIR_BEHIND_LEFT
            }
        }
    }
}

// imperial = the rider's distance unit is feet/miles.
private fun AppSettings.imperial(): Boolean = unitDistance == "mi" || unitDistance == "ft"

// --- inlined nav strings (default-locale values from strings.xml) ---
private const val NAV_START_RIDING = "Start riding"
private const val NAV_ARRIVED = "You have arrived"
private fun navArrivedGoal(n: Int) = "Reached goal $n"
private const val NAV_OFF_ROUTE = "Off route"
private const val NAV_RECALCULATING = "Recalculating…"
private const val NAV_CONTINUE = "Continue straight"
private const val NAV_TURN_LEFT = "Turn left"
private const val NAV_TURN_RIGHT = "Turn right"
private const val NAV_TURN_SLIGHT_LEFT = "Bear left"
private const val NAV_TURN_SLIGHT_RIGHT = "Bear right"
private const val NAV_TURN_SHARP_LEFT = "Sharp left"
private const val NAV_TURN_SHARP_RIGHT = "Sharp right"
private const val NAV_UTURN = "Make a U-turn"
private const val NAV_DEPART = "Head out"
private const val NAV_LABEL_NEXT_STOP = "Next stop"
private const val NAV_LABEL_DESTINATION = "Destination"
private const val NAV_DIR_AHEAD = "ahead"
private const val NAV_DIR_LEFT = "on your left"
private const val NAV_DIR_RIGHT = "on your right"
private const val NAV_DIR_SLIGHT_LEFT = "slightly left"
private const val NAV_DIR_SLIGHT_RIGHT = "slightly right"
private const val NAV_DIR_BEHIND = "behind you"
private const val NAV_DIR_BEHIND_LEFT = "behind you, on your left"
private const val NAV_DIR_BEHIND_RIGHT = "behind you, on your right"
private const val VOICE_GOAL_REACHED = "Stop reached"
private const val VOICE_ARRIVED = "You have arrived at your destination"
private const val VOICE_PROX_HOT = "you are very close"
private const val VOICE_PROX_WARMER = "getting warmer"
private const val VOICE_PROX_COLDER = "getting colder"
private const val VOICE_PROX_EVEN_COLDER = "even colder"
private const val VOICE_PROX_FREEZING = "freezing cold"
private const val VOICE_PROX_LOST = "Are we there yet?"
private fun voicePrepare(dist: String, turn: String) = "In $dist, $turn"
private fun voiceNow(turn: String) = "$turn now"
private fun voiceHunt(label: String, dist: String, dir: String) = "$label is $dist $dir"
private fun voiceHuntProx(base: String, prox: String) = "$base, $prox"
private fun voiceHuntDistance(label: String, dist: String) = "$label is $dist away"
