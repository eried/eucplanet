package com.eried.eucplanet.wear.bridge

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.eried.eucplanet.hud.protocol.WatchMapFrame
import com.eried.eucplanet.hud.protocol.WatchMapPresence
import com.eried.eucplanet.hud.protocol.WatchMapProtocol
import com.eried.eucplanet.hud.protocol.WatchMapRoute
import com.eried.eucplanet.hud.protocol.WatchMapRouteKey
import com.eried.eucplanet.hud.protocol.WatchMapSession
import com.eried.eucplanet.hud.protocol.WatchMapSnapshot
import com.eried.eucplanet.hud.protocol.WatchMapTileKey
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Process-wide owner of the watch side of the phone-backed map transport. */
object WatchMapRepository {
    private const val TAG = "WatchMap"
    private const val MAX_DECODED_BYTES = 8 * 1024 * 1024
    private const val MAX_TILE_SOURCE_DIMENSION = 16_384

    private sealed interface TilePayload {
        data class AssetBacked(val asset: Asset) : TilePayload
        data class Inline(val bytes: ByteArray) : TilePayload
    }

    private data class TileAssetCandidate(
        val sourceNodeId: String,
        val key: WatchMapTileKey,
        val deliveryGeneration: Long,
        val asset: Asset,
    )

    private class TileReadExpectation(
        val sourceNodeId: String,
        val keys: Set<WatchMapTileKey>,
    )

    private data class PendingRoute(
        val sourceNodeId: String,
        val key: WatchMapRouteKey,
        val deliveryGeneration: Long,
        val asset: Asset,
    )

    private data class RouteExpectation(
        val sourceNodeId: String,
        val key: WatchMapRouteKey,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = WatchMapSession()
    private val decodeSlots = Semaphore(4)

    private val _snapshot = MutableStateFlow(WatchMapSnapshot(null, null, false, false))
    val snapshot: StateFlow<WatchMapSnapshot> = _snapshot.asStateFlow()

    private val _tiles = MutableStateFlow<Map<WatchMapTileKey, Bitmap>>(emptyMap())
    val tiles: StateFlow<Map<WatchMapTileKey, Bitmap>> = _tiles.asStateFlow()

    private val _tileFailures = MutableStateFlow<Set<WatchMapTileKey>>(emptySet())
    val tileFailures: StateFlow<Set<WatchMapTileKey>> = _tileFailures.asStateFlow()

    private val _zoom = MutableStateFlow(WatchMapProtocol.DEFAULT_ZOOM)
    val zoom: StateFlow<Int> = _zoom.asStateFlow()

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null
    private var viewerId = ""
    private var viewerEpoch = 0L
    private var presenceSequence = 0L
    private var phoneNodeId: String? = null
    private var enabled = false
    private var showTelemetry = true
    private var resumed = false
    private var interactive = false
    private var mapPageSelected = false
    @Volatile private var tileDecodeVisible = false

    private var heartbeatJob: Job? = null
    private var expiryJob: Job? = null

    private var cachedFrame: WatchMapFrame? = null
    private var cachedRoute: WatchMapRoute? = null
    private var lastAutoOpenSession = ""
    private var lastSceneCenterTile: WatchMapTileKey? = null

    private val bitmapCache = object : LinkedHashMap<WatchMapTileKey, Bitmap>(32, 0.75f, true) {}
    private var bitmapCacheBytes = 0
    private val pendingTiles = PendingTileStore<TilePayload>()
    private val tileLoads = mutableMapOf<
        WatchMapTileKey,
        PendingTileStore.Entry<TilePayload>,
    >()
    private var visibleTiles: List<WatchMapTileKey> = emptyList()
    private var tileDataReadExpectation: TileReadExpectation? = null
    private var tileDataReadSignature: List<WatchMapTileKey> = emptyList()
    private var routeReadExpectation: RouteExpectation? = null
    private var pendingRoute: PendingRoute? = null
    private var routeLoadInFlight: PendingRoute? = null

    /** Must run before Compose reads [WatchStateRepository.state]. */
    fun hydrateMapConfiguration(context: Context) {
        ensureInitialized(context)
        WatchStateRepository.hydrateMapConfiguration(enabled, showTelemetry)
        publishSnapshot(SystemClock.elapsedRealtime())
        val source = phoneNodeId
        if (source != null) {
            readPersistedConfiguration(source)
            cachedFrame?.routeKey()?.let { readRouteBeforeMissing(source, it) }
        }
    }

    fun acceptConfiguration(
        context: Context,
        sourceNodeId: String,
        mapEnabled: Boolean,
        showTelemetry: Boolean,
    ) {
        ensureInitialized(context)
        if (sourceNodeId.isBlank()) return
        scope.launch {
            applyConfiguration(sourceNodeId, mapEnabled, showTelemetry)
        }
    }

    fun acceptFrame(context: Context, sourceNodeId: String, payload: ByteArray) {
        ensureInitialized(context)
        val copied = payload.copyOf()
        scope.launch {
            val frame = withContext(Dispatchers.Default) {
                WatchMapProtocol.decodeFrame(copied)
            } ?: return@launch
            if (!_foreground.value || sourceNodeId != phoneNodeId) return@launch
            val previous = displayFrame()
            val now = SystemClock.elapsedRealtime()
            if (!session.acceptFrame(frame, now)) return@launch

            cachedFrame = null
            if (frame.enabled != enabled) {
                enabled = frame.enabled
                persistConfiguration()
                WatchStateRepository.hydrateMapConfiguration(enabled, showTelemetry)
            }
            val expectedRoute = frame.routeKey()
            if (cachedRoute?.matches(expectedRoute) != true) cachedRoute = null
            cachedRoute?.let(session::acceptRoute)
            if (pendingRoute?.let {
                    it.sourceNodeId == sourceNodeId && it.key == expectedRoute
                } != true
            ) {
                pendingRoute = null
                routeLoadInFlight = null
            }
            if (routeReadExpectation?.let {
                    it.sourceNodeId == sourceNodeId && it.key == expectedRoute
                } != true
            ) {
                routeReadExpectation = null
            }
            publishSnapshot(now)

            if (shouldPersistFrame(previous, frame)) persistScene(frame)
            if (expectedRoute != null && session.snapshot(now).route == null) {
                readRouteBeforeMissing(sourceNodeId, expectedRoute)
            }
            updateSubscription()
        }
    }

    fun acceptRouteAsset(
        context: Context,
        sourceNodeId: String,
        navigationSessionId: String,
        revision: Long,
        deliveryGeneration: Long,
        asset: Asset,
    ) {
        ensureInitialized(context)
        if (navigationSessionId.isBlank() || revision <= 0L || deliveryGeneration < 0L) return
        val pending = PendingRoute(
            sourceNodeId = sourceNodeId,
            key = WatchMapRouteKey(navigationSessionId, revision),
            deliveryGeneration = deliveryGeneration,
            asset = asset,
        )
        scope.launch {
            if (sourceNodeId != phoneNodeId) return@launch
            retainPendingRoute(pending)
            processPendingRoute()
        }
    }

    fun acceptTileAsset(
        context: Context,
        sourceNodeId: String,
        key: WatchMapTileKey,
        deliveryGeneration: Long,
        asset: Asset,
    ) {
        ensureInitialized(context)
        if (!isValidTileKey(key) || deliveryGeneration < 0L) return
        scope.launch {
            if (sourceNodeId != phoneNodeId || key in bitmapCache) return@launch
            val expectedPath = tilePath(key)
            if (!expectedPath.startsWith(WatchMapProtocol.TILE_PREFIX)) return@launch
            val offered = pendingTiles.offer(
                sourceNodeId,
                key,
                deliveryGeneration,
                TilePayload.AssetBacked(asset),
            )
            if (offered) {
                processVisiblePendingTiles()
            }
        }
    }

    fun acceptTileMessage(
        context: Context,
        sourceNodeId: String,
        payload: ByteArray,
    ) {
        ensureInitialized(context)
        val copied = payload.copyOf()
        scope.launch {
            val message = withContext(Dispatchers.Default) {
                WatchMapProtocol.decodeTileMessage(copied)
            } ?: return@launch
            val key = message.key
            if (sourceNodeId != phoneNodeId ||
                key !in visibleTiles ||
                !tileDecodeVisible ||
                key in bitmapCache
            ) {
                return@launch
            }
            val offered = pendingTiles.offer(
                sourceNodeId,
                key,
                message.deliveryGeneration,
                TilePayload.Inline(message.encodedTile.copyOf()),
                replaceSameGeneration = true,
            )
            if (offered) {
                processVisiblePendingTiles()
            }
        }
    }

    fun setActivityResumed(context: Context, value: Boolean) {
        ensureInitialized(context)
        scope.launch {
            resumed = value
            updateSubscription()
        }
    }

    fun setInteractive(context: Context, value: Boolean) {
        ensureInitialized(context)
        scope.launch {
            interactive = value
            updateSubscription()
        }
    }

    fun setMapPageSelected(value: Boolean) {
        scope.launch {
            if (mapPageSelected == value) return@launch
            if (mapPageSelected && !value) persistScene(displayFrame())
            mapPageSelected = value
            updateMapVisibility()
        }
    }

    /** Keys must be ordered nearest-first by the renderer. */
    fun setVisibleTiles(keys: List<WatchMapTileKey>) {
        scope.launch {
            val valid = keys.distinct().filter(::isValidTileKey)
            if (valid == visibleTiles) return@launch
            visibleTiles = valid
            pendingTiles.retain(phoneNodeId.takeIf { tileDecodeVisible }, valid.takeIf { tileDecodeVisible } ?: emptyList())
            _tileFailures.value = _tileFailures.value intersect valid.toSet()
            valid.forEach { bitmapCache[it] }
            publishTiles()
            val center = valid.firstOrNull()
            if (center != null && center != lastSceneCenterTile) {
                lastSceneCenterTile = center
                persistScene(displayFrame())
            }
            tileDataReadExpectation = null
            tileDataReadSignature = emptyList()
            if (tileDecodeVisible) {
                readExistingTileItems()
                requestPresenceNow()
                processVisiblePendingTiles()
            }
        }
    }
    fun setZoom(value: Int) {
        val clamped = value.coerceIn(WatchMapProtocol.MIN_ZOOM, WatchMapProtocol.MAX_ZOOM)
        if (_zoom.value == clamped) return
        _zoom.value = clamped
        preferences?.edit()?.putInt(WatchMapPrefs.ZOOM, clamped)?.apply()
        tileDataReadSignature = emptyList()
        requestPresenceNow()
    }

    /** Persists the decision before the caller starts pager animation. */
    fun claimAutoOpen(navigationSessionId: String): Boolean {
        if (navigationSessionId.isBlank() || navigationSessionId == lastAutoOpenSession) return false
        lastAutoOpenSession = navigationSessionId
        preferences?.edit()
            ?.putString(WatchMapPrefs.LAST_AUTO_OPEN_SESSION, navigationSessionId)
            ?.commit()
        return true
    }

    private fun ensureInitialized(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            val application = context.applicationContext
            val prefs = application.getSharedPreferences(WatchMapPrefs.NAME, Context.MODE_PRIVATE)
            val storedViewerId = prefs.getString(WatchMapPrefs.VIEWER_ID, null)
            viewerId = storedViewerId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(WatchMapPrefs.VIEWER_ID, it).commit()
            }
            viewerEpoch = prefs.getLong(WatchMapPrefs.VIEWER_EPOCH, 0L).coerceAtLeast(0L)
            phoneNodeId = prefs.getString(WatchMapPrefs.PHONE_NODE_ID, null)?.takeIf { it.isNotBlank() }
            enabled = prefs.getBoolean(WatchMapPrefs.ENABLED, false)
            showTelemetry = prefs.getBoolean(WatchMapPrefs.SHOW_TELEMETRY, true)
            _zoom.value = prefs.getInt(WatchMapPrefs.ZOOM, WatchMapProtocol.DEFAULT_ZOOM)
                .coerceIn(WatchMapProtocol.MIN_ZOOM, WatchMapProtocol.MAX_ZOOM)
            lastAutoOpenSession = prefs.getString(WatchMapPrefs.LAST_AUTO_OPEN_SESSION, "").orEmpty()
            cachedFrame = prefs.getString(WatchMapPrefs.FRAME_JSON, null)?.let { json ->
                WatchMapProtocol.decodeFrame(json.toByteArray(Charsets.UTF_8))
            }
            preferences = prefs
            appContext = application
        }
    }

    private suspend fun applyConfiguration(
        sourceNodeId: String,
        mapEnabled: Boolean,
        showTelemetry: Boolean,
    ) {
        val sourceChanged = phoneNodeId != null && phoneNodeId != sourceNodeId
        if (sourceChanged && _foreground.value) stopForegroundSubscription()
        if (sourceChanged) {
            cachedFrame = null
            cachedRoute = null
            pendingTiles.retain(null, emptyList())
            tileLoads.clear()
            routeReadExpectation = null
            pendingRoute = null
            routeLoadInFlight = null
            tileDataReadExpectation = null
            tileDataReadSignature = emptyList()
            _tileFailures.value = emptySet()
            publishSnapshot(SystemClock.elapsedRealtime())
        }
        phoneNodeId = sourceNodeId
        pendingTiles.retain(phoneNodeId.takeIf { tileDecodeVisible }, visibleTiles.takeIf { tileDecodeVisible } ?: emptyList())
        enabled = mapEnabled
        this.showTelemetry = showTelemetry
        persistConfiguration()
        WatchStateRepository.hydrateMapConfiguration(enabled, this.showTelemetry)
        updateSubscription()
    }

    private fun persistConfiguration() {
        preferences?.edit()
            ?.putString(WatchMapPrefs.PHONE_NODE_ID, phoneNodeId)
            ?.putBoolean(WatchMapPrefs.ENABLED, enabled)
            ?.putBoolean(WatchMapPrefs.SHOW_TELEMETRY, showTelemetry)
            ?.apply()
    }

    private fun updateSubscription() {
        val shouldRun = enabled && resumed && interactive && phoneNodeId != null
        when {
            shouldRun && !_foreground.value -> startForegroundSubscription()
            !shouldRun && _foreground.value -> stopForegroundSubscription()
            else -> updateMapVisibility()
        }
    }

    private fun startForegroundSubscription() {
        if (viewerEpoch == Long.MAX_VALUE) {
            viewerId = UUID.randomUUID().toString()
            viewerEpoch = 0L
        } else {
            viewerEpoch += 1L
        }
        preferences?.edit()
            ?.putString(WatchMapPrefs.VIEWER_ID, viewerId)
            ?.putLong(WatchMapPrefs.VIEWER_EPOCH, viewerEpoch)
            ?.commit()
        presenceSequence = 0L
        session.begin(viewerId, viewerEpoch)
        _foreground.value = true
        _tileFailures.value = emptySet()
        updateMapVisibility()
        publishSnapshot(SystemClock.elapsedRealtime())
        displayFrame()?.routeKey()?.let { phoneNodeId?.let { source -> readRouteBeforeMissing(source, it) } }
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            sendPresence(foreground = true, mapVisible = tileDecodeVisible)
            while (true) {
                delay(WatchMapProtocol.PRESENCE_INTERVAL_MS)
                sendPresence(foreground = true, mapVisible = tileDecodeVisible)
            }
        }
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (true) {
                delay(1_000L)
                publishSnapshot(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun stopForegroundSubscription() {
        sendPresence(foreground = false, mapVisible = false)
        heartbeatJob?.cancel()
        heartbeatJob = null
        expiryJob?.cancel()
        expiryJob = null
        _foreground.value = false
        updateMapVisibility()
        publishSnapshot(SystemClock.elapsedRealtime())
    }

    private fun updateMapVisibility() {
        val visible = _foreground.value && enabled && mapPageSelected
        if (tileDecodeVisible == visible) return
        tileDecodeVisible = visible
        if (visible) {
            pendingTiles.retain(phoneNodeId, visibleTiles)
            processPendingRoute()
            tileDataReadExpectation = null
            tileDataReadSignature = emptyList()
            readExistingTileItems()
            processVisiblePendingTiles()
        } else {
            pendingTiles.retain(phoneNodeId, emptyList())
            _tileFailures.value = emptySet()
            tileDataReadExpectation = null
            tileDataReadSignature = emptyList()
            persistScene(displayFrame())
        }
        requestPresenceNow()
    }

    private fun requestPresenceNow() {
        if (_foreground.value) sendPresence(foreground = true, mapVisible = tileDecodeVisible)
    }

    private fun sendPresence(foreground: Boolean, mapVisible: Boolean) {
        val context = appContext ?: return
        val target = phoneNodeId ?: return
        val sequence = presenceSequence++
        val now = SystemClock.elapsedRealtime()
        val snapshot = session.snapshot(now)
        val currentFrame = snapshot.frame ?: cachedFrame
        val routeKey = currentFrame?.routeKey()
        val routePresent = snapshot.route?.matches(routeKey) == true || cachedRoute?.matches(routeKey) == true
        val missingRoute = routeKey?.takeIf { expected ->
            !routePresent &&
                routeReadExpectation?.key != expected &&
                pendingRoute?.let {
                    it.sourceNodeId == target && it.key == expected
                } != true
        }
        val missingTiles = if (mapVisible) nextMissingTiles(currentFrame) else emptyList()
        val presence = WatchMapPresence(
            viewerId = viewerId,
            viewerEpoch = viewerEpoch,
            sequence = sequence,
            foreground = foreground,
            mapVisible = mapVisible,
            missingRoute = missingRoute,
            missingTiles = missingTiles,
        )
        val payload = WatchMapProtocol.encodePresence(presence) ?: return
        session.sentPresence(sequence, now)
        scope.launch(Dispatchers.IO) {
            runCatching {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(target, WatchMapProtocol.PRESENCE_PATH, payload),
                )
            }.onFailure { Log.d(TAG, "Presence send failed", it) }
        }
    }

    private fun nextMissingTiles(frame: WatchMapFrame?): List<WatchMapTileKey> {
        val unavailable = frame?.unavailableTiles.orEmpty().toSet()
        val candidates = visibleTiles.filter { key ->
            key.layerId == frame?.layerId &&
                key !in bitmapCache &&
                key !in pendingTiles &&
                key !in tileLoads &&
                key !in tileDataReadExpectation?.keys.orEmpty()
        }
        return (candidates.filterNot { it in unavailable } + candidates.filter { it in unavailable })
            .take(WatchMapProtocol.MAX_TILE_REQUESTS)
    }

    private fun readPersistedConfiguration(sourceNodeId: String) {
        val context = appContext ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val uri = dataUri(WatchPaths.STATE, sourceNodeId)
                val buffer = Tasks.await(
                    Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL),
                )
                try {
                    buffer.firstOrNull()?.let { item ->
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val persistedEnabled = if (map.containsKey(WatchKeys.MAP_ENABLED)) {
                            map.getBoolean(WatchKeys.MAP_ENABLED)
                        } else {
                            null
                        }
                        val persistedShowTelemetry =
                            if (map.containsKey(WatchKeys.MAP_SHOW_TELEMETRY)) {
                                map.getBoolean(WatchKeys.MAP_SHOW_TELEMETRY)
                            } else {
                                null
                            }
                        persistedEnabled to persistedShowTelemetry
                    }
                } finally {
                    buffer.release()
                }
            }.getOrNull()
            if (result != null) {
                scope.launch {
                    if (phoneNodeId == sourceNodeId) {
                        applyConfiguration(
                            sourceNodeId,
                            result.first ?: enabled,
                            result.second ?: showTelemetry,
                        )
                    }
                }
            }
        }
    }

    private fun readRouteBeforeMissing(sourceNodeId: String, key: WatchMapRouteKey) {
        val expectation = RouteExpectation(sourceNodeId, key)
        if (sourceNodeId != phoneNodeId || displayFrame()?.routeKey() != key) return
        if (pendingRoute?.let { it.sourceNodeId == sourceNodeId && it.key == key } == true) {
            routeReadExpectation = null
            processPendingRoute()
            return
        }
        if (routeReadExpectation == expectation) return
        routeReadExpectation = expectation
        val context = appContext ?: run {
            routeReadExpectation = null
            return
        }
        scope.launch(Dispatchers.IO) {
            val copied = runCatching {
                val buffer = Tasks.await(
                    Wearable.getDataClient(context).getDataItems(
                        dataUri(WatchMapProtocol.ROUTE_PATH, sourceNodeId),
                        DataClient.FILTER_LITERAL,
                    ),
                )
                try {
                    buffer.firstOrNull()?.let { item ->
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val routeKey = WatchMapRouteKey(
                            navigationSessionId = map.getString(
                                WatchMapProtocol.ROUTE_SESSION_KEY,
                            ).orEmpty(),
                            revision = map.getLong(WatchMapProtocol.ROUTE_REVISION_KEY, -1L),
                        )
                        val generation = map.getLong(
                            WatchMapProtocol.ROUTE_DELIVERY_GENERATION_KEY,
                            -1L,
                        )
                        val asset = map.getAsset(WatchMapProtocol.ROUTE_GEOMETRY_KEY)
                        if (item.uri.host == sourceNodeId &&
                            item.uri.path == WatchMapProtocol.ROUTE_PATH &&
                            map.getInt(WatchMapProtocol.ROUTE_VERSION_KEY, -1) ==
                                WatchMapProtocol.VERSION &&
                            routeKey == key && generation >= 0L && asset != null
                        ) {
                            PendingRoute(sourceNodeId, routeKey, generation, asset)
                        } else {
                            null
                        }
                    }
                } finally {
                    buffer.release()
                }
            }.getOrNull()
            scope.launch {
                if (routeReadExpectation != expectation) return@launch
                routeReadExpectation = null
                if (sourceNodeId != phoneNodeId || displayFrame()?.routeKey() != key) return@launch
                if (copied == null) {
                    requestPresenceNow()
                } else {
                    retainPendingRoute(copied)
                    processPendingRoute()
                    requestPresenceNow()
                }
            }
        }
    }

    private fun retainPendingRoute(candidate: PendingRoute) {
        if (candidate.sourceNodeId != phoneNodeId) return
        val current = pendingRoute
        if (current == null ||
            current.sourceNodeId != candidate.sourceNodeId ||
            candidate.deliveryGeneration >= current.deliveryGeneration
        ) {
            pendingRoute = candidate
        }
    }

    private fun processPendingRoute() {
        if (!tileDecodeVisible) return
        val pending = pendingRoute ?: return
        if (pending.sourceNodeId != phoneNodeId) {
            pendingRoute = null
            routeLoadInFlight = null
            return
        }
        val currentKey = displayFrame()?.routeKey()
        if (currentKey == null) return
        if (currentKey != pending.key) {
            pendingRoute = null
            routeLoadInFlight = null
            return
        }
        if (routeLoadInFlight == pending) return
        val context = appContext ?: return
        routeLoadInFlight = pending
        scope.launch {
            if (!isCurrentVisibleRoute(pending)) {
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) {
                readAssetBytes(context, pending.asset, maxBytes = null)
            }
            if (!isCurrentVisibleRoute(pending)) {
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                if (tileDecodeVisible) processPendingRoute()
                return@launch
            }
            if (bytes == null) {
                if (pendingRoute == pending) pendingRoute = null
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                requestPresenceNow()
                return@launch
            }
            if (!isCurrentVisibleRoute(pending)) {
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                return@launch
            }
            val route = withContext(Dispatchers.Default) {
                if (tileDecodeVisible) WatchMapProtocol.decodeRoute(bytes) else null
            }
            if (!isCurrentVisibleRoute(pending)) {
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                if (tileDecodeVisible) processPendingRoute()
                return@launch
            }
            if (route?.matches(pending.key) != true) {
                if (pendingRoute == pending) pendingRoute = null
                if (routeLoadInFlight == pending) routeLoadInFlight = null
                requestPresenceNow()
                return@launch
            }
            if (!session.acceptRoute(route)) cachedRoute = route else cachedRoute = null
            if (pendingRoute == pending) pendingRoute = null
            if (routeLoadInFlight == pending) routeLoadInFlight = null
            publishSnapshot(SystemClock.elapsedRealtime())
            requestPresenceNow()
        }
    }

    private fun isCurrentVisibleRoute(pending: PendingRoute): Boolean =
        tileDecodeVisible &&
            pending.sourceNodeId == phoneNodeId &&
            displayFrame()?.routeKey() == pending.key &&
            pendingRoute == pending

    private fun readExistingTileItems() {
        val context = appContext ?: return
        val source = phoneNodeId ?: return
        if (!tileDecodeVisible) return
        val requested = visibleTiles.filter {
            it !in bitmapCache &&
                it !in pendingTiles &&
                it !in tileLoads &&
                it !in tileDataReadExpectation?.keys.orEmpty()
        }
        if (requested.isEmpty() || requested == tileDataReadSignature) return
        val expectation = TileReadExpectation(source, requested.toSet())
        tileDataReadExpectation = expectation
        tileDataReadSignature = requested
        scope.launch(Dispatchers.IO) {
            val copied = runCatching {
                val buffer = Tasks.await(
                    Wearable.getDataClient(context).getDataItems(
                        dataUri(WatchMapProtocol.TILE_PREFIX, source),
                        DataClient.FILTER_PREFIX,
                    ),
                )
                try {
                    buffer.mapNotNull { item ->
                        if (item.uri.host != source) return@mapNotNull null
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val key = WatchMapTileKey(
                            layerId = map.getString(WatchMapProtocol.TILE_LAYER_KEY).orEmpty(),
                            z = map.getInt(WatchMapProtocol.TILE_Z_KEY, -1),
                            x = map.getInt(WatchMapProtocol.TILE_X_KEY, -1),
                            y = map.getInt(WatchMapProtocol.TILE_Y_KEY, -1),
                        )
                        val asset = map.getAsset(WatchMapProtocol.TILE_PNG_KEY)
                        val generation = map.getLong(WatchMapProtocol.TILE_DELIVERY_GENERATION_KEY, -1L)
                        if (map.getInt(WatchMapProtocol.TILE_VERSION_KEY, -1) == WatchMapProtocol.VERSION &&
                            key in expectation.keys && item.uri.path == tilePath(key) &&
                            generation >= 0L && asset != null
                        ) {
                            TileAssetCandidate(source, key, generation, asset)
                        } else {
                            null
                        }
                    }
                } finally {
                    buffer.release()
                }
            }.getOrDefault(emptyList())
            scope.launch {
                if (tileDataReadExpectation !== expectation ||
                    source != phoneNodeId ||
                    !tileDecodeVisible
                ) {
                    return@launch
                }
                tileDataReadExpectation = null
                copied.forEach { pending ->
                    if (pending.key !in bitmapCache) {
                        pendingTiles.offer(
                            pending.sourceNodeId,
                            pending.key,
                            pending.deliveryGeneration,
                            TilePayload.AssetBacked(pending.asset),
                        )
                    }
                }
                processVisiblePendingTiles()
                requestPresenceNow()
            }
        }
    }

    private fun processVisiblePendingTiles() {
        if (!tileDecodeVisible) return
        val context = appContext ?: return
        visibleTiles.forEach { key ->
            val pending = pendingTiles[key] ?: return@forEach
            if (key in bitmapCache || tileLoads[key] != null) return@forEach
            tileLoads[key] = pending
            scope.launch {
                var attemptedDecode = false
                val bitmap = decodeSlots.withPermit {
                    if (!tileDecodeVisible ||
                        pending.sourceNodeId != phoneNodeId ||
                        pendingTiles[key] !== pending ||
                        key !in visibleTiles
                    ) {
                        return@withPermit null
                    }
                    attemptedDecode = true
                    withContext(Dispatchers.IO) {
                        val bytes = when (val value = pending.value) {
                            is TilePayload.Inline -> value.bytes
                            is TilePayload.AssetBacked -> readAssetBytes(
                                context,
                                value.asset,
                                maxBytes = WatchMapProtocol.MAX_TILE_ASSET_BYTES,
                            )
                        } ?: return@withContext null
                        runCatching { decodeTile(bytes) }.getOrNull()
                    }
                }
                val completion = pendingTiles.complete(pending)
                if (tileLoads[key] === pending) tileLoads.remove(key)
                val current = tileDecodeVisible &&
                    pending.sourceNodeId == phoneNodeId &&
                    key in visibleTiles
                if (bitmap != null && completion.accepted && current) {
                    putBitmap(key, bitmap)
                    _tileFailures.value = _tileFailures.value - key
                } else {
                    if (bitmap != null) bitmap.recycle()
                    if (completion.accepted && attemptedDecode && current) {
                        _tileFailures.value = _tileFailures.value + key
                    }
                }
                requestPresenceNow()
                if (completion.replacement != null && tileDecodeVisible) {
                    processVisiblePendingTiles()
                }
            }
        }
    }
    private fun putBitmap(key: WatchMapTileKey, bitmap: Bitmap) {
        bitmapCache.remove(key)?.let { bitmapCacheBytes -= it.allocationByteCount }
        bitmapCache[key] = bitmap
        bitmapCacheBytes += bitmap.allocationByteCount
        while (bitmapCacheBytes > MAX_DECODED_BYTES && bitmapCache.isNotEmpty()) {
            val eldest = bitmapCache.entries.first()
            bitmapCache.remove(eldest.key)
            bitmapCacheBytes -= eldest.value.allocationByteCount
        }
        publishTiles()
    }

    private fun publishTiles() {
        _tiles.value = LinkedHashMap(bitmapCache)
    }

    private fun publishSnapshot(nowElapsedMs: Long) {
        val live = session.snapshot(nowElapsedMs)
        if (live.frame != null) {
            _snapshot.value = live
            return
        }
        val frame = cachedFrame
        _snapshot.value = WatchMapSnapshot(
            frame = frame,
            route = cachedRoute?.takeIf { it.matches(frame?.routeKey()) },
            linkLive = false,
            fixLive = false,
        )
    }

    private fun displayFrame(): WatchMapFrame? = session.snapshot(SystemClock.elapsedRealtime()).frame ?: cachedFrame

    private fun persistScene(frame: WatchMapFrame?) {
        val encoded = frame?.let(WatchMapProtocol::encodeFrame)?.toString(Charsets.UTF_8)
        val editor = preferences?.edit()?.putInt(WatchMapPrefs.ZOOM, _zoom.value) ?: return
        if (encoded == null) editor.remove(WatchMapPrefs.FRAME_JSON)
        else editor.putString(WatchMapPrefs.FRAME_JSON, encoded)
        editor.apply()
    }

    private fun shouldPersistFrame(previous: WatchMapFrame?, current: WatchMapFrame): Boolean =
        (previous?.fix == null && current.fix != null) ||
            previous?.layerId != current.layerId ||
            previous?.routeKey() != current.routeKey()

    private fun WatchMapFrame.routeKey(): WatchMapRouteKey? =
        if (navigationActive && navigationSessionId.isNotBlank() && routeRevision > 0L) {
            WatchMapRouteKey(navigationSessionId, routeRevision)
        } else {
            null
        }

    private fun WatchMapRoute.matches(key: WatchMapRouteKey?): Boolean =
        key != null && navigationSessionId == key.navigationSessionId && revision == key.revision

    private fun isValidTileKey(key: WatchMapTileKey): Boolean {
        if (key.layerId.isBlank() || key.z !in WatchMapProtocol.MIN_ZOOM..WatchMapProtocol.MAX_ZOOM) return false
        val side = 1 shl key.z
        return key.x in 0 until side && key.y in 0 until side
    }

    private fun tilePath(key: WatchMapTileKey): String =
        "${WatchMapProtocol.TILE_PREFIX}${key.layerId}/${key.z}/${key.x}/${key.y}"

    private fun dataUri(path: String, sourceNodeId: String): Uri = Uri.Builder()
        .scheme(PutDataRequest.WEAR_URI_SCHEME)
        .authority(sourceNodeId)
        .path(path)
        .build()

    private fun readAssetBytes(context: Context, asset: Asset, maxBytes: Int?): ByteArray? {
        val response = runCatching {
            Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset))
        }.getOrNull() ?: return null
        return try {
            runCatching {
                response.inputStream?.use { input ->
                    if (maxBytes == null) input.readBytes() else input.readLimitedBytes(maxBytes)
                }
            }.getOrNull()
        } finally {
            response.release()
        }
    }

    private fun InputStream.readLimitedBytes(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(32 * 1024, limit))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(chunk, 0, read)
        }
        return output.toByteArray()
    }

    private fun decodeTile(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width != height ||
            width > MAX_TILE_SOURCE_DIMENSION || height > MAX_TILE_SOURCE_DIMENSION
        ) {
            return null
        }
        var sampleSize = 1
        while (width / sampleSize > 256 || height / sampleSize > 256) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.width <= 256 && decoded.height <= 256) return decoded
        val scale = minOf(256f / decoded.width, 256f / decoded.height)
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (resized !== decoded) decoded.recycle()
        return resized
    }
}
