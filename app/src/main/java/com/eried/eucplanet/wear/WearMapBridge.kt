package com.eried.eucplanet.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.hud.protocol.MapLayers
import com.eried.eucplanet.hud.protocol.WatchMapCue
import com.eried.eucplanet.hud.protocol.WatchMapFix
import com.eried.eucplanet.hud.protocol.WatchMapFrame
import com.eried.eucplanet.hud.protocol.WatchMapLeases
import com.eried.eucplanet.hud.protocol.WatchMapLeasesSnapshot
import com.eried.eucplanet.hud.protocol.WatchMapLocationStatus
import com.eried.eucplanet.hud.protocol.WatchMapPoint
import com.eried.eucplanet.hud.protocol.WatchMapPresence
import com.eried.eucplanet.hud.protocol.WatchMapProtocol
import com.eried.eucplanet.hud.protocol.WatchMapRoute
import com.eried.eucplanet.hud.protocol.WatchMapRouteKey
import com.eried.eucplanet.hud.protocol.WatchMapTileKey
import com.eried.eucplanet.hud.protocol.WatchMapTileMessage
import com.eried.eucplanet.map.MapTileCache
import com.eried.eucplanet.nav.NavigationEngine
import com.eried.eucplanet.service.WheelService
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class WearMapBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val navigationEngine: NavigationEngine,
) {
    private companion object {
        const val TAG = "WearMapBridge"
        const val PREFS_NAME = "wear_map_presence"
        const val PREFS_HISTORY = "lease_history"
        const val PREFS_TILE_INDEX = "published_tile_index"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_PUBLISHED_TILES = 64
        const val MAX_TILE_LOADS = 4
        const val MAX_TILE_WRITES = 4
        const val MAX_TILE_MESSAGES = 4
        const val TILE_FAILURE_COOLDOWN_MS = 30_000L
        const val TILE_RECONCILE_RETRY_MS = 30_000L
    }

    private sealed interface Event {
        data class Presence(val sourceNodeId: String, val payload: ByteArray) : Event
        data class Settings(val settings: AppSettings) : Event
        data object NavigationChanged : Event
        data class RoutePrepared(
            val key: WatchMapRouteKey,
            val bytes: ByteArray?,
        ) : Event
        data class TileIndexReady(val records: List<PublishedTile>?) : Event
        data class TileLoaded(
            val key: WatchMapTileKey,
            val token: Long,
            val png: ByteArray?,
        ) : Event
        data class TileWriteFinished(
            val attemptId: Long,
            val key: WatchMapTileKey,
            val path: String,
            val deliveryGeneration: Long,
            val lastRequestedWallMs: Long,
            val evictionPath: String?,
            val evictionDeleted: Boolean,
            val success: Boolean,
        ) : Event
        data object Tick : Event
        data object ServiceStarted : Event
        data object ServiceStopped : Event
    }

    private sealed interface DataWrite {
        data object ReconcileTiles : DataWrite
        data class RoutePut(
            val key: WatchMapRouteKey,
            val bytes: ByteArray,
            val deliveryGeneration: Long,
        ) : DataWrite

        data object RouteDelete : DataWrite

        data class TilePut(
            val attemptId: Long,
            val key: WatchMapTileKey,
            val path: String,
            val bytes: ByteArray,
            val deliveryGeneration: Long,
            val lastRequestedWallMs: Long,
            val evictionPath: String?,
        ) : DataWrite

        data class TileMessage(
            val targetNodeId: String,
            val key: WatchMapTileKey,
            val payload: ByteArray,
        ) : DataWrite
    }

    private data class PreparedRoute(
        val key: WatchMapRouteKey,
        val bytes: ByteArray,
        var deliveryGeneration: Long = 0L,
    )

    private data class PreparedTile(
        val key: WatchMapTileKey,
        val bytes: ByteArray,
        var deliveryGeneration: Long,
        var lastOfferElapsedMs: Long? = null,
    )

    private data class PublishedTile(
        val path: String,
        val lastRequestedWallMs: Long,
        val deliveryGeneration: Long,
    )

    private data class PendingTileWrite(
        val attemptId: Long,
        val path: String,
        val evictionPath: String?,
    )

    private data class ActiveTileLoad(
        val token: Long,
        val job: Job,
    )

    private data class ViewerSession(
        val sourceNodeId: String,
        val viewerId: String,
        val viewerEpoch: Long,
    )

    private data class FrameLocation(
        val status: WatchMapLocationStatus,
        val fix: WatchMapFix?,
        val anchor: WatchMapPoint?,
        val headingDeg: Float?,
        val fixMaxAgeMs: Long,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val dataWrites = Channel<DataWrite>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private val leases = WatchMapLeases()
    private val phoneSessionId = UUID.randomUUID().toString()
    private val preferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val tilePrefixUri by lazy { dataUri(WatchMapProtocol.TILE_PREFIX) }

    private var gpsDemand = false
    private var frameSequence = 0L
    private var lastHeadingDeg: Float? = null

    private var routePublishingAllowed: Boolean? = null
    private var routeSessionId: String? = null
    private var routeLegReference: NavRoute? = null
    private var routeGeometry: DoubleArray? = null
    private var routeRevision = 0L
    private var routeTarget: WatchMapPoint? = null
    private var currentRouteKey: WatchMapRouteKey? = null
    private var preparedRoute: PreparedRoute? = null
    private var preparingRouteKey: WatchMapRouteKey? = null
    private var lastMissingRepublishKey: WatchMapRouteKey? = null
    private var lastMissingRepublishElapsedMs = 0L

    @Volatile
    private var desiredRouteKey: WatchMapRouteKey? = null

    private var tileIndexReady = false
    private var tileWorkEnabled = false
    private var tileReconcileRetryJob: Job? = null
    /** Play Services has no Wearable module on this phone (GrapheneOS with
     *  a sandboxed Play, phones with no Wear support, bare emulators). The
     *  first reconcile learns it; nothing is retried after that, since a
     *  retry every 30 s for the life of the process cannot succeed and only
     *  costs a Play Services bind and a stack trace each time. */
    @Volatile
    private var wearableUnavailable = false
    private var nextTileLoadToken = 0L
    private var nextTileWriteAttempt = 0L
    private var desiredTileKeys: Set<WatchMapTileKey> = emptySet()
    private var visibleViewerSessions: Set<ViewerSession> = emptySet()
    private val tileLoadQueue = ArrayDeque<WatchMapTileKey>()
    private val activeTileLoads = mutableMapOf<WatchMapTileKey, ActiveTileLoad>()
    private val preparedTiles = mutableMapOf<WatchMapTileKey, PreparedTile>()
    private val tileFailuresUntil = mutableMapOf<WatchMapTileKey, Long>()
    private val tileLastRequestedWallMs = mutableMapOf<WatchMapTileKey, Long>()
    private val pendingTileWrites = mutableMapOf<WatchMapTileKey, PendingTileWrite>()
    private val publishedTiles = mutableMapOf<String, PublishedTile>()

    @Volatile
    private var allowedTileWriteKeys: Set<WatchMapTileKey> = emptySet()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        restoreHistory()

        scope.launch { runDataWriter() }
        scope.launch { runActor() }

        scope.launch {
            settingsRepository.settings
                .distinctUntilChanged()
                .collect { events.send(Event.Settings(it)) }
        }
        scope.launch {
            navigationEngine.navState.collect {
                events.send(Event.NavigationChanged)
            }
        }
        scope.launch {
            navigationEngine.activeLeg.collect {
                events.send(Event.NavigationChanged)
            }
        }
    }

    fun onPresence(sourceNodeId: String, payload: ByteArray) {
        start()
        events.trySend(Event.Presence(sourceNodeId, payload))
    }

    fun onPublisherTick() {
        start()
        events.trySend(Event.Tick)
    }

    fun onServiceStarted() {
        start()
        events.trySend(Event.ServiceStarted)
    }

    fun onServiceStopped() {
        if (!started.get()) return
        events.trySend(Event.ServiceStopped)
    }

    private suspend fun runActor() {
        var settings = settingsRepository.get()
        var serviceActive = false

        updateRoutePublishingAllowed(false)
        refreshRouteSnapshot()
        reevaluate(settings.watchMap.enabled, serviceActive, SystemClock.elapsedRealtime())

        for (event in events) {
            val nowMs = SystemClock.elapsedRealtime()
            var acceptedPresence: WatchMapPresence? = null
            when (event) {
                is Event.Presence -> {
                    val presence = WatchMapProtocol.decodePresence(event.payload)
                    if (presence != null &&
                        leases.accept(event.sourceNodeId, presence, nowMs)
                    ) {
                        acceptedPresence = presence
                        persistHistory()
                    }
                }
                is Event.Settings -> settings = event.settings
                Event.NavigationChanged -> Unit
                is Event.RoutePrepared -> acceptPreparedRoute(event)
                is Event.TileIndexReady -> acceptTileIndex(event.records)
                is Event.TileLoaded -> acceptTileLoad(event, nowMs)
                is Event.TileWriteFinished -> acceptTileWrite(event)
                Event.Tick -> leases.expire(nowMs)
                Event.ServiceStarted -> {
                    // This gate is intentionally idempotent. A second foreground
                    // promotion must not discard leases accepted by this service.
                    if (!serviceActive) leases.clear()
                    serviceActive = true
                }
                Event.ServiceStopped -> {
                    serviceActive = false
                    leases.clear()
                }
            }

            if (!serviceActive || !settings.watchMap.enabled) leases.clear()
            updateRoutePublishingAllowed(settings.watchMap.enabled && serviceActive)
            refreshRouteSnapshot()

            val allowTileLoads = acceptedPresence != null ||
                event is Event.Settings ||
                event === Event.ServiceStarted ||
                event is Event.TileIndexReady
            updateTileRequests(
                settings = settings,
                serviceActive = serviceActive,
                nowMs = nowMs,
                allowNewLoads = allowTileLoads,
                touchRequests = acceptedPresence?.foreground == true &&
                    acceptedPresence.mapVisible,
            )

            acceptedPresence?.let {
                handleMissingRoute(it, nowMs)
                handleMissingTiles(it, settings, nowMs)
            }
            reevaluate(settings.watchMap.enabled, serviceActive, nowMs)

            if (event === Event.Tick && settings.watchMap.enabled && serviceActive) {
                publishFrames(settings, nowMs)
            }
        }
    }

    private fun updateRoutePublishingAllowed(allowed: Boolean) {
        if (routePublishingAllowed == allowed) return
        routePublishingAllowed = allowed
        if (!allowed) {
            desiredRouteKey = null
            dataWrites.trySend(DataWrite.RouteDelete)
            return
        }
        publishCurrentRoute()
    }

    /** Reads navigation around the leg read so sessions and geometry stay paired. */
    private fun refreshRouteSnapshot() {
        val firstNav = navigationEngine.navState.value
        val leg = navigationEngine.activeLeg.value
        val secondNav = navigationEngine.navState.value

        if (!secondNav.active) {
            clearActiveRoute()
            return
        }
        if (!firstNav.active ||
            firstNav.sessionId.isBlank() ||
            firstNav.sessionId != secondNav.sessionId
        ) {
            return
        }

        applyActiveRoute(secondNav.sessionId, leg)
    }

    private fun applyActiveRoute(sessionId: String, leg: NavRoute?) {
        val newSession = routeSessionId != sessionId
        if (!newSession && routeLegReference === leg) return

        if (newSession) {
            if (routeSessionId != null && routePublishingAllowed == true) {
                desiredRouteKey = null
                dataWrites.trySend(DataWrite.RouteDelete)
            }
            routeSessionId = sessionId
            routeLegReference = null
            routeGeometry = null
            routeRevision = 0L
            routeTarget = null
            replaceCurrentRouteKey(null)
        }

        routeLegReference = leg
        routeTarget = targetFor(leg)
        val geometry = flattenGeometry(leg)
        if (!newSession && geometryContentEquals(routeGeometry, geometry)) return

        routeGeometry = geometry
        if (geometry == null) {
            replaceCurrentRouteKey(null)
            if (routePublishingAllowed == true) {
                dataWrites.trySend(DataWrite.RouteDelete)
            }
            return
        }

        routeRevision += 1L
        val key = WatchMapRouteKey(sessionId, routeRevision)
        replaceCurrentRouteKey(key)
        if (routePublishingAllowed == true) publishCurrentRoute()
    }

    private fun clearActiveRoute() {
        if (routeSessionId == null) return
        routeSessionId = null
        routeLegReference = null
        routeGeometry = null
        routeRevision = 0L
        routeTarget = null
        replaceCurrentRouteKey(null)
        if (routePublishingAllowed == true) {
            dataWrites.trySend(DataWrite.RouteDelete)
        }
    }

    private fun replaceCurrentRouteKey(key: WatchMapRouteKey?) {
        if (currentRouteKey == key) return
        currentRouteKey = key
        preparedRoute = null
        preparingRouteKey = null
        lastMissingRepublishKey = null
        lastMissingRepublishElapsedMs = 0L
        desiredRouteKey = if (routePublishingAllowed == true) key else null
    }

    private fun publishCurrentRoute() {
        val key = currentRouteKey ?: run {
            desiredRouteKey = null
            return
        }
        desiredRouteKey = key
        val prepared = preparedRoute
        if (prepared?.key == key) {
            enqueuePreparedRoute(prepared)
            return
        }
        if (preparingRouteKey == key) return

        val geometry = routeGeometry?.copyOf() ?: return
        preparingRouteKey = key
        scope.launch(Dispatchers.Default) {
            val bytes = WatchMapProtocol.encodeRoute(
                WatchMapRoute(
                    navigationSessionId = key.navigationSessionId,
                    revision = key.revision,
                    coordinates = geometry,
                ),
            )
            events.send(Event.RoutePrepared(key, bytes))
        }
    }

    private fun acceptPreparedRoute(event: Event.RoutePrepared) {
        if (preparingRouteKey == event.key) preparingRouteKey = null
        if (currentRouteKey != event.key) return
        val bytes = event.bytes
        if (bytes == null) {
            Log.w(TAG, "Route encoding failed for ${event.key}")
            replaceCurrentRouteKey(null)
            if (routePublishingAllowed == true) {
                dataWrites.trySend(DataWrite.RouteDelete)
            }
            return
        }

        val prepared = PreparedRoute(event.key, bytes)
        preparedRoute = prepared
        if (routePublishingAllowed == true) enqueuePreparedRoute(prepared)
    }

    private fun enqueuePreparedRoute(prepared: PreparedRoute) {
        if (routePublishingAllowed != true || currentRouteKey != prepared.key) return
        desiredRouteKey = prepared.key
        prepared.deliveryGeneration += 1L
        dataWrites.trySend(
            DataWrite.RoutePut(
                key = prepared.key,
                bytes = prepared.bytes,
                deliveryGeneration = prepared.deliveryGeneration,
            ),
        )
    }

    private fun handleMissingRoute(presence: WatchMapPresence, nowMs: Long) {
        if (!presence.foreground || routePublishingAllowed != true) return
        val key = currentRouteKey ?: return
        if (presence.missingRoute != key) return
        val prepared = preparedRoute?.takeIf { it.key == key } ?: return
        if (lastMissingRepublishKey == key &&
            nowMs - lastMissingRepublishElapsedMs < WatchMapProtocol.LEASE_MS
        ) {
            return
        }

        lastMissingRepublishKey = key
        lastMissingRepublishElapsedMs = nowMs
        enqueuePreparedRoute(prepared)
    }

    private fun acceptTileIndex(records: List<PublishedTile>?) {
        if (records == null) {
            tileIndexReady = false
            scheduleTileReconciliationRetry()
            return
        }
        tileReconcileRetryJob?.cancel()
        tileReconcileRetryJob = null
        publishedTiles.clear()
        records.forEach { publishedTiles[it.path] = it }
        tileIndexReady = true
    }

    private fun scheduleTileReconciliationRetry() {
        if (wearableUnavailable) return
        if (tileReconcileRetryJob?.isActive == true) return
        tileReconcileRetryJob = scope.launch {
            delay(TILE_RECONCILE_RETRY_MS)
            dataWrites.send(DataWrite.ReconcileTiles)
        }
    }

    private fun updateTileRequests(
        settings: AppSettings,
        serviceActive: Boolean,
        nowMs: Long,
        allowNewLoads: Boolean,
        touchRequests: Boolean,
    ) {
        val enabled = settings.watchMap.enabled && serviceActive && tileIndexReady
        if (!enabled) {
            if (tileWorkEnabled || desiredTileKeys.isNotEmpty()) clearTileWork()
            tileWorkEnabled = false
            return
        }
        tileWorkEnabled = true

        val layer = MapLayers.byId(settings.navMapType)
        val visibleSubscribers = leases.subscribers(nowMs)
            .filter { it.foreground && it.mapVisible }
            .sortedWith(compareBy({ it.sourceNodeId }, { it.viewerId }, { it.viewerEpoch }))
        val sessions = visibleSubscribers.mapTo(linkedSetOf()) {
            ViewerSession(it.sourceNodeId, it.viewerId, it.viewerEpoch)
        }
        if ((sessions - visibleViewerSessions).isNotEmpty()) {
            tileFailuresUntil.clear()
        }
        visibleViewerSessions = sessions

        tileFailuresUntil.entries.removeAll { (_, untilMs) -> nowMs >= untilMs }
        val requested = linkedSetOf<WatchMapTileKey>()
        visibleSubscribers.forEach { subscriber ->
            subscriber.missingTiles.forEach { key ->
                if (isTileValidForLayer(key, layer)) requested += key
            }
        }

        if (requested != desiredTileKeys) {
            desiredTileKeys = requested
            allowedTileWriteKeys = requested.toSet()

            val obsoleteLoads = activeTileLoads.keys.filter { it !in requested }
            obsoleteLoads.forEach { key -> activeTileLoads.remove(key)?.job?.cancel() }
            preparedTiles.keys.retainAll(requested)
            tileLastRequestedWallMs.keys.retainAll(requested)

            val stillQueued = tileLoadQueue.filterTo(linkedSetOf()) { it in requested }
            tileLoadQueue.clear()
            tileLoadQueue.addAll(stillQueued)
        } else {
            allowedTileWriteKeys = requested.toSet()
        }

        if (touchRequests) {
            val wallMs = System.currentTimeMillis()
            requested.forEach { tileLastRequestedWallMs[it] = wallMs }
            touchPublishedTiles(requested, wallMs)
        }

        if (allowNewLoads) {
            requested.forEach { key ->
                val coolingDown = tileFailuresUntil[key]?.let { nowMs < it } == true
                if (!coolingDown &&
                    key !in preparedTiles &&
                    key !in pendingTileWrites &&
                    key !in activeTileLoads &&
                    key !in tileLoadQueue
                ) {
                    tileLoadQueue.addLast(key)
                }
            }
        }
        pumpTileLoads()
    }

    private fun pumpTileLoads() {
        if (!tileWorkEnabled || !tileIndexReady) return
        while (activeTileLoads.size < MAX_TILE_LOADS && tileLoadQueue.isNotEmpty()) {
            val key = tileLoadQueue.removeFirst()
            if (key !in desiredTileKeys ||
                key in preparedTiles ||
                key in pendingTileWrites ||
                key in activeTileLoads
            ) {
                continue
            }
            val token = ++nextTileLoadToken
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val png = try {
                    val url = MapLayers.tileUrl(key.layerId, key.z, key.x, key.y)
                    MapTileCache.encodedTile(context, url)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.d(TAG, "Tile load failed for $key", e)
                    null
                }
                events.send(Event.TileLoaded(key, token, png))
            }
            activeTileLoads[key] = ActiveTileLoad(token, job)
            job.start()
        }
    }

    private fun acceptTileLoad(event: Event.TileLoaded, nowMs: Long) {
        val active = activeTileLoads[event.key] ?: return
        if (active.token != event.token) return
        activeTileLoads.remove(event.key)

        if (tileWorkEnabled && event.key in desiredTileKeys) {
            val png = event.png
            if (png == null) {
                tileFailuresUntil[event.key] = nowMs + TILE_FAILURE_COOLDOWN_MS
            } else {
                tileFailuresUntil.remove(event.key)
                val path = tilePath(event.key)
                val prepared = PreparedTile(
                    key = event.key,
                    bytes = png,
                    deliveryGeneration = publishedTiles[path]?.deliveryGeneration ?: 0L,
                )
                preparedTiles[event.key] = prepared
                enqueueTilePublication(prepared, nowMs)
            }
        }
        pumpTileLoads()
    }
    private fun clearTileWork() {
        allowedTileWriteKeys = emptySet()
        desiredTileKeys = emptySet()
        visibleViewerSessions = emptySet()
        tileLoadQueue.clear()
        activeTileLoads.values.forEach { it.job.cancel() }
        activeTileLoads.clear()
        preparedTiles.clear()
        tileFailuresUntil.clear()
        tileLastRequestedWallMs.clear()
    }

    private fun handleMissingTiles(
        presence: WatchMapPresence,
        settings: AppSettings,
        nowMs: Long,
    ) {
        if (!tileWorkEnabled || !presence.foreground || !presence.mapVisible) return
        val layer = MapLayers.byId(settings.navMapType)
        presence.missingTiles.distinct().forEach { key ->
            if (!isTileValidForLayer(key, layer) || key !in desiredTileKeys) return@forEach
            val prepared = preparedTiles[key] ?: return@forEach
            val lastOffer = prepared.lastOfferElapsedMs
            if (lastOffer != null && nowMs - lastOffer < WatchMapProtocol.LEASE_MS) {
                return@forEach
            }
            enqueueTilePublication(prepared, nowMs)
        }
    }

    private fun enqueueTilePublication(prepared: PreparedTile, nowMs: Long) {
        val key = prepared.key
        if (!tileWorkEnabled || key !in desiredTileKeys || key in pendingTileWrites) return
        val path = tilePath(key)
        val evictionPath = chooseTileEviction(path) ?: if (projectedPublishedTileCount(path) >=
            MAX_PUBLISHED_TILES && path !in publishedTiles
        ) {
            return
        } else {
            null
        }

        prepared.deliveryGeneration += 1L
        prepared.lastOfferElapsedMs = nowMs
        val fastPayload = WatchMapProtocol.encodeTileMessage(
            WatchMapTileMessage(
                key = key,
                deliveryGeneration = prepared.deliveryGeneration,
                encodedTile = prepared.bytes,
            ),
        )
        if (fastPayload != null) {
            leases.subscribers(nowMs)
                .asSequence()
                .filter { it.foreground && it.mapVisible && key in it.missingTiles }
                .map { it.sourceNodeId }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { targetNodeId ->
                    dataWrites.trySend(
                        DataWrite.TileMessage(
                            targetNodeId = targetNodeId,
                            key = key,
                            payload = fastPayload,
                        ),
                    )
                }
        }
        val attemptId = ++nextTileWriteAttempt
        val lastRequested = tileLastRequestedWallMs[key] ?: System.currentTimeMillis()
        pendingTileWrites[key] = PendingTileWrite(attemptId, path, evictionPath)
        dataWrites.trySend(
            DataWrite.TilePut(
                attemptId = attemptId,
                key = key,
                path = path,
                bytes = prepared.bytes,
                deliveryGeneration = prepared.deliveryGeneration,
                lastRequestedWallMs = lastRequested,
                evictionPath = evictionPath,
            ),
        )
    }

    private fun chooseTileEviction(newPath: String): String? {
        if (newPath in publishedTiles || projectedPublishedTileCount(newPath) < MAX_PUBLISHED_TILES) {
            return null
        }
        val alreadyEvicting = pendingTileWrites.values.mapNotNullTo(mutableSetOf()) {
            it.evictionPath
        }
        return publishedTiles.values
            .asSequence()
            .filter { it.path !in alreadyEvicting && it.path != newPath }
            .minWithOrNull(compareBy<PublishedTile>({ it.lastRequestedWallMs }, { it.path }))
            ?.path
    }

    private fun projectedPublishedTileCount(newPath: String): Int {
        val paths = publishedTiles.keys.toMutableSet()
        pendingTileWrites.values.forEach { pending ->
            pending.evictionPath?.let(paths::remove)
            paths += pending.path
        }
        if (newPath in paths) return paths.size
        return paths.size
    }

    private fun acceptTileWrite(event: Event.TileWriteFinished) {
        val pending = pendingTileWrites[event.key] ?: return
        if (pending.attemptId != event.attemptId) return
        pendingTileWrites.remove(event.key)

        if (event.evictionDeleted) event.evictionPath?.let(publishedTiles::remove)
        if (event.success) {
            publishedTiles[event.path] = PublishedTile(
                path = event.path,
                lastRequestedWallMs = event.lastRequestedWallMs,
                deliveryGeneration = event.deliveryGeneration,
            )
            tileFailuresUntil.remove(event.key)
        }
        persistTileIndex(publishedTiles.values)
    }

    private fun touchPublishedTiles(keys: Set<WatchMapTileKey>, wallMs: Long) {
        var changed = false
        keys.forEach { key ->
            val path = tilePath(key)
            val old = publishedTiles[path] ?: return@forEach
            if (old.lastRequestedWallMs != wallMs) {
                publishedTiles[path] = old.copy(lastRequestedWallMs = wallMs)
                changed = true
            }
        }
        if (changed) persistTileIndex(publishedTiles.values)
    }

    /** The only coroutine that waits for DataClient tasks. */
    private suspend fun runDataWriter() {
        val dataClient = Wearable.getDataClient(context)
        val messageClient = Wearable.getMessageClient(context)
        val tileWriteSlots = Semaphore(MAX_TILE_WRITES)
        val tileMessageSlots = Semaphore(MAX_TILE_MESSAGES)
        var localNodeId: String? = null

        suspend fun reconcileTiles() {
            val reconciled = runCatching {
                val nodeId = localNodeId ?: Tasks.await(
                    Wearable.getNodeClient(context).localNode,
                ).id.takeIf { it.isNotBlank() }
                    ?: error("Local Wear node id unavailable")
                localNodeId = nodeId
                reconcilePublishedTiles(dataClient, nodeId)
            }
                .onFailure { e ->
                    if (isWearableUnavailable(e)) {
                        if (!wearableUnavailable) {
                            Log.i(TAG, "No Wearable API on this phone, watch map stays idle: ${e.message}")
                        }
                        wearableUnavailable = true
                    } else {
                        Log.w(TAG, "Tile DataItem reconciliation failed", e)
                    }
                }
                .getOrNull()
            events.send(Event.TileIndexReady(reconciled))
        }

        reconcileTiles()
        for (write in dataWrites) {
            when (write) {
                DataWrite.ReconcileTiles -> reconcileTiles()
                DataWrite.RouteDelete -> writeRouteDelete(dataClient, localNodeId)
                is DataWrite.RoutePut -> writeRoutePut(dataClient, write)
                is DataWrite.TileMessage -> scope.launch(Dispatchers.IO) {
                    tileMessageSlots.withPermit {
                        writeTileMessage(messageClient, write)
                    }
                }
                is DataWrite.TilePut -> scope.launch(Dispatchers.IO) {
                    tileWriteSlots.withPermit {
                        writeTilePut(dataClient, write, localNodeId)
                    }
                }
            }
        }
    }

    private fun writeRouteDelete(dataClient: DataClient, localNodeId: String?) {
        try {
            Tasks.await(
                dataClient.deleteDataItems(

                    dataUri(WatchMapProtocol.ROUTE_PATH, localNodeId),
                    DataClient.FILTER_LITERAL,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Route DataItem delete failed", e)
        }
    }

    private fun writeRoutePut(dataClient: DataClient, write: DataWrite.RoutePut) {
        if (desiredRouteKey != write.key) return
        try {
            val request = PutDataMapRequest.create(WatchMapProtocol.ROUTE_PATH).apply {
                dataMap.putInt(WatchMapProtocol.ROUTE_VERSION_KEY, WatchMapProtocol.VERSION)
                dataMap.putString(
                    WatchMapProtocol.ROUTE_SESSION_KEY,
                    write.key.navigationSessionId,
                )
                dataMap.putLong(WatchMapProtocol.ROUTE_REVISION_KEY, write.key.revision)
                dataMap.putLong(
                    WatchMapProtocol.ROUTE_DELIVERY_GENERATION_KEY,
                    write.deliveryGeneration,
                )
                dataMap.putAsset(
                    WatchMapProtocol.ROUTE_GEOMETRY_KEY,
                    Asset.createFromBytes(write.bytes),
                )
            }.asPutDataRequest().setUrgent()
            if (desiredRouteKey != write.key) return
            Tasks.await(dataClient.putDataItem(request))
        } catch (e: Exception) {
            Log.w(TAG, "Route DataItem put failed", e)
        }
    }

    private suspend fun writeTileMessage(
        messageClient: com.google.android.gms.wearable.MessageClient,
        write: DataWrite.TileMessage,
    ) {
        if (write.targetNodeId.isBlank() || write.key !in allowedTileWriteKeys) return
        try {
            if (write.key !in allowedTileWriteKeys) return
            Tasks.await(
                messageClient.sendMessage(
                    write.targetNodeId,
                    WatchMapProtocol.TILE_MESSAGE_PATH,
                    write.payload,
                ),
            )
        } catch (_: Exception) {
        }
    }
    private suspend fun writeTilePut(
        dataClient: DataClient,
        write: DataWrite.TilePut,
        localNodeId: String?,
    ) {
        var evictionDeleted = false
        var success = false
        try {
            if (localNodeId.isNullOrBlank() || write.key !in allowedTileWriteKeys) return
            val evictionPath = write.evictionPath
            if (evictionPath != null) {
                if (!evictionPath.startsWith(WatchMapProtocol.TILE_PREFIX)) return
                Tasks.await(
                    dataClient.deleteDataItems(
                        dataUri(evictionPath, localNodeId),
                        DataClient.FILTER_LITERAL,
                    ),
                )
                evictionDeleted = true
            }
            if (write.key !in allowedTileWriteKeys) return

            val request = PutDataMapRequest.create(write.path).apply {
                dataMap.putInt(WatchMapProtocol.TILE_VERSION_KEY, WatchMapProtocol.VERSION)
                dataMap.putString(WatchMapProtocol.TILE_LAYER_KEY, write.key.layerId)
                dataMap.putInt(WatchMapProtocol.TILE_Z_KEY, write.key.z)
                dataMap.putInt(WatchMapProtocol.TILE_X_KEY, write.key.x)
                dataMap.putInt(WatchMapProtocol.TILE_Y_KEY, write.key.y)
                dataMap.putLong(
                    WatchMapProtocol.TILE_DELIVERY_GENERATION_KEY,
                    write.deliveryGeneration,
                )
                dataMap.putAsset(
                    WatchMapProtocol.TILE_PNG_KEY,
                    Asset.createFromBytes(write.bytes),
                )
            }.asPutDataRequest().setUrgent()
            if (write.key !in allowedTileWriteKeys) return
            Tasks.await(dataClient.putDataItem(request))
            success = true
        } catch (e: Exception) {
            Log.w(TAG, "Tile DataItem write failed for ${write.key}", e)
        } finally {
            events.send(
                Event.TileWriteFinished(
                    attemptId = write.attemptId,
                    key = write.key,
                    path = write.path,
                    deliveryGeneration = write.deliveryGeneration,
                    lastRequestedWallMs = write.lastRequestedWallMs,
                    evictionPath = write.evictionPath,
                    evictionDeleted = evictionDeleted,
                    success = success,
                ),
            )
        }
    }

    private fun reconcilePublishedTiles(
        dataClient: DataClient,
        localNodeId: String,
    ): List<PublishedTile> {
        val savedTimes = readPersistedTileTimes()
        val found = mutableMapOf<String, Pair<Uri, PublishedTile>>()
        val buffer = Tasks.await(
            dataClient.getDataItems(tilePrefixUri, DataClient.FILTER_PREFIX),
        )
        try {
            for (item in buffer) {
                if (item.uri.host != localNodeId) continue
                val path = item.uri.path ?: continue
                if (!path.startsWith(WatchMapProtocol.TILE_PREFIX)) continue
                val generation = runCatching {
                    DataMapItem.fromDataItem(item).dataMap.getLong(
                        WatchMapProtocol.TILE_DELIVERY_GENERATION_KEY,
                    )
                }.getOrDefault(0L)
                found[path] = item.uri to PublishedTile(
                    path = path,
                    lastRequestedWallMs = savedTimes[path] ?: 0L,
                    deliveryGeneration = generation,
                )
            }
        } finally {
            buffer.release()
        }

        val excess = (found.size - MAX_PUBLISHED_TILES).coerceAtLeast(0)
        found.values
            .sortedWith(compareBy({ it.second.lastRequestedWallMs }, { it.second.path }))
            .take(excess)
            .forEach { (uri, record) ->
                Tasks.await(dataClient.deleteDataItems(uri, DataClient.FILTER_LITERAL))
                found.remove(record.path)
            }

        val records = found.values.map { it.second }
        persistTileIndex(records)
        return records
    }

    private fun publishFrames(settings: AppSettings, nowMs: Long) {
        val subscribers = leases.subscribers(nowMs)
        if (subscribers.isEmpty()) return

        val location = frameLocation(settings)
        val nav = navigationEngine.navState.value
        val activeSessionId = if (nav.active) nav.sessionId else ""
        val announcedRoute = currentRouteKey?.takeIf {
            nav.active && it.navigationSessionId == activeSessionId
        }
        val target = routeTarget?.takeIf {
            nav.active && routeSessionId == activeSessionId
        }
        val cue = if (nav.active &&
            settings.watchShowNavigation &&
            location.status == WatchMapLocationStatus.LIVE
        ) {
            WatchMapCue(
                angleDeg = nav.arrowAngleDeg(),
                primary = nav.primaryText,
                distance = nav.distanceText,
                arrived = nav.arrived,
            )
        } else {
            null
        }
        val layer = MapLayers.byId(settings.navMapType)

        subscribers.forEach { subscriber ->
            val unavailableTiles = if (subscriber.mapVisible) {
                subscriber.missingTiles
                    .asSequence()
                    .filter { isTileValidForLayer(it, layer) }
                    .filter { key -> tileFailuresUntil[key]?.let { nowMs < it } == true }
                    .distinct()
                    .take(WatchMapProtocol.MAX_TILE_REQUESTS)
                    .toList()
            } else {
                emptyList()
            }
            val frame = WatchMapFrame(
                phoneSessionId = phoneSessionId,
                viewerId = subscriber.viewerId,
                viewerEpoch = subscriber.viewerEpoch,
                presenceSequence = subscriber.presenceSequence,
                sequence = frameSequence++,
                enabled = settings.watchMap.enabled,
                headingUp = settings.watchMap.headingUp,
                layerId = layer.id,
                unavailableTiles = unavailableTiles,
                locationStatus = location.status,
                fix = location.fix,
                anchor = location.anchor,
                fixMaxAgeMs = location.fixMaxAgeMs,
                headingDeg = location.headingDeg,
                navigationSessionId = activeSessionId,
                navigationActive = nav.active,
                routeRevision = announcedRoute?.revision ?: 0L,
                target = target,
                cue = cue,
            )
            val payload = WatchMapProtocol.encodeFrame(frame) ?: return@forEach
            try {
                messageClient
                    .sendMessage(
                        subscriber.sourceNodeId,
                        WatchMapProtocol.FRAME_PATH,
                        payload,
                    )
                    .addOnFailureListener { error ->
                        Log.d(
                            TAG,
                            "Frame send to ${subscriber.sourceNodeId} failed: ${error.message}",
                        )
                    }
            } catch (e: Exception) {
                Log.d(TAG, "Frame send to ${subscriber.sourceNodeId} failed", e)
            }
        }
    }

    private fun frameLocation(settings: AppSettings): FrameLocation {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val fixMaxAgeMs = settings.gpsFixMaxAgeSec.toLong() * 1_000L
        val current = tripRepository.currentLocation.value
        val currentPoint = current?.toWatchMapPoint()
        val currentAgeMs = current?.let {
            elapsedAgeMs(nowNanos, it.elapsedRealtimeNanos)
        }
        val anchor = tripRepository.lastKnownLocation.value?.toWatchMapPoint()

        val status = when {
            !hasLocationPermission() -> WatchMapLocationStatus.PERMISSION_REQUIRED
            !WheelService.locationForegroundReady -> WatchMapLocationStatus.OPEN_PHONE
            currentPoint != null &&
                currentAgeMs != null &&
                currentAgeMs <= fixMaxAgeMs -> WatchMapLocationStatus.LIVE
            anchor != null -> WatchMapLocationStatus.STALE_FIX
            else -> WatchMapLocationStatus.WAITING_FIX
        }
        val liveLocation = current?.takeIf { status == WatchMapLocationStatus.LIVE }
        if (liveLocation != null &&
            liveLocation.hasBearing() &&
            liveLocation.hasSpeed() &&
            liveLocation.bearing.isFinite() &&
            liveLocation.speed.isFinite() &&
            liveLocation.speed >= settings.navMovingKmh.toFloat() / 3.6f
        ) {
            lastHeadingDeg = normalizeHeading(liveLocation.bearing)
        }

        return FrameLocation(
            status = status,
            fix = if (status == WatchMapLocationStatus.LIVE) {
                WatchMapFix(
                    point = currentPoint!!,
                    ageMs = currentAgeMs!!,
                )
            } else {
                null
            },
            anchor = anchor,
            headingDeg = lastHeadingDeg,
            fixMaxAgeMs = fixMaxAgeMs,
        )
    }

    private fun reevaluate(enabled: Boolean, serviceActive: Boolean, nowMs: Long) {
        if (!enabled) leases.clear()
        val shouldDemandGps = enabled &&
            serviceActive &&
            WheelService.locationForegroundReady &&
            hasLocationPermission() &&
            leases.hasVisibleMap(nowMs)
        if (shouldDemandGps == gpsDemand) return
        gpsDemand = shouldDemandGps
        tripRepository.setWatchMapVisible(shouldDemandGps)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isTileValidForLayer(
        key: WatchMapTileKey,
        layer: MapLayers.Layer,
    ): Boolean {
        if (key.layerId != layer.id) return false
        if (key.z !in WatchMapProtocol.MIN_ZOOM..WatchMapProtocol.MAX_ZOOM) return false
        if (key.z > layer.maxNativeZoom) return false
        val side = 1 shl key.z
        return key.x in 0 until side && key.y in 0 until side
    }

    private fun tilePath(key: WatchMapTileKey): String =
        "${WatchMapProtocol.TILE_PREFIX}${key.layerId}/${key.z}/${key.x}/${key.y}"

    private fun dataUri(path: String, authority: String? = null): Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .apply {
            if (!authority.isNullOrBlank()) authority(authority)
        }
        .path(path)
        .build()

    private fun flattenGeometry(route: NavRoute?): DoubleArray? {
        val geometry = route?.geometry ?: return null
        if (geometry.isEmpty()) return null
        val flat = DoubleArray(geometry.size * 2)
        geometry.forEachIndexed { index, point ->
            if (!isValidPoint(point.lat, point.lng)) return null
            flat[index * 2] = point.lat
            flat[index * 2 + 1] = point.lng
        }
        return flat
    }

    private fun targetFor(route: NavRoute?): WatchMapPoint? {
        val waypoint = route?.waypoints?.lastOrNull()
        if (waypoint != null && isValidPoint(waypoint.lat, waypoint.lng)) {
            return WatchMapPoint(waypoint.lat, waypoint.lng)
        }
        val geometry = route?.geometry?.lastOrNull() ?: return null
        return if (isValidPoint(geometry.lat, geometry.lng)) {
            WatchMapPoint(geometry.lat, geometry.lng)
        } else {
            null
        }
    }

    private fun Location.toWatchMapPoint(): WatchMapPoint? =
        if (isValidPoint(latitude, longitude)) {
            WatchMapPoint(latitude, longitude)
        } else {
            null
        }

    private fun isValidPoint(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lat in -90.0..90.0 &&
            lon.isFinite() && lon in -180.0..180.0

    private fun geometryContentEquals(first: DoubleArray?, second: DoubleArray?): Boolean =
        first === second ||
            (first != null && second != null && first.contentEquals(second))

    private fun elapsedAgeMs(nowNanos: Long, fixNanos: Long): Long {
        if (fixNanos >= nowNanos) return 0L
        if (fixNanos < 0L) return Long.MAX_VALUE
        return (nowNanos - fixNanos) / NANOS_PER_MILLISECOND
    }

    private fun normalizeHeading(heading: Float): Float {
        val normalized = heading % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    private fun restoreHistory() {
        val raw = preferences.getString(PREFS_HISTORY, null) ?: return
        val snapshot = runCatching {
            WatchMapProtocol.json.decodeFromString(WatchMapLeasesSnapshot.serializer(), raw)
        }.getOrNull() ?: return
        leases.restore(snapshot)
    }

    private fun persistHistory() {
        val raw = runCatching {
            WatchMapProtocol.json.encodeToString(WatchMapLeasesSnapshot.serializer(), leases.snapshot())
        }.getOrNull() ?: return
        preferences.edit().putString(PREFS_HISTORY, raw).apply()
    }

    private fun readPersistedTileTimes(): Map<String, Long> {
        val raw = preferences.getString(PREFS_TILE_INDEX, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    if (path.startsWith(WatchMapProtocol.TILE_PREFIX)) {
                        put(path, json.optLong(path, 0L))
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persistTileIndex(records: Collection<PublishedTile>) {
        val raw = JSONObject().apply {
            records.forEach { record -> put(record.path, record.lastRequestedWallMs) }
        }.toString()
        preferences.edit().putString(PREFS_TILE_INDEX, raw).apply()
    }
}

/**
 * True when a Play Services failure means the Wearable API does not exist on
 * this phone at all (status 17, API_NOT_CONNECTED with an API_UNAVAILABLE
 * result), as opposed to a watch that is merely out of reach. The first is
 * permanent for the process; the second is worth retrying.
 */
internal fun isWearableUnavailable(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) {
        if (t is com.google.android.gms.common.api.AvailabilityException) return true
        if (t is com.google.android.gms.common.api.ApiException &&
            t.statusCode == com.google.android.gms.common.api.CommonStatusCodes.API_NOT_CONNECTED
        ) return true
        t = t.cause
    }
    return false
}
