package com.eried.eucplanet.hud.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class WatchMapPoint(
    val lat: Double,
    val lon: Double,
)

@Serializable
data class WatchMapFix(
    val point: WatchMapPoint,
    val ageMs: Long,
)

@Serializable
data class WatchMapCue(
    val angleDeg: Float,
    val primary: String,
    val distance: String,
    val arrived: Boolean,
)

@Serializable
data class WatchMapTileKey(
    val layerId: String,
    val z: Int,
    val x: Int,
    val y: Int,
)

data class WatchMapTileMessage(
    val key: WatchMapTileKey,
    val deliveryGeneration: Long,
    val encodedTile: ByteArray,
)

@Serializable
data class WatchMapRouteKey(
    val navigationSessionId: String,
    val revision: Long,
)

@Serializable
enum class WatchMapLocationStatus {
    LIVE,
    WAITING_FIX,
    STALE_FIX,
    PERMISSION_REQUIRED,
    OPEN_PHONE,
}

@Serializable
data class WatchMapPresence(
    val version: Int = WatchMapProtocol.VERSION,
    val viewerId: String,
    val viewerEpoch: Long,
    val sequence: Long,
    val foreground: Boolean,
    val mapVisible: Boolean,
    val missingRoute: WatchMapRouteKey?,
    val missingTiles: List<WatchMapTileKey>,
)

@Serializable
data class WatchMapFrame(
    val version: Int = WatchMapProtocol.VERSION,
    val phoneSessionId: String,
    val viewerId: String,
    val viewerEpoch: Long,
    val presenceSequence: Long,
    val sequence: Long,
    val enabled: Boolean,
    val headingUp: Boolean,
    val layerId: String,
    val unavailableTiles: List<WatchMapTileKey>,
    val locationStatus: WatchMapLocationStatus,
    val fix: WatchMapFix?,
    val anchor: WatchMapPoint?,
    val fixMaxAgeMs: Long,
    val headingDeg: Float?,
    val navigationSessionId: String,
    val navigationActive: Boolean,
    val routeRevision: Long,
    val target: WatchMapPoint?,
    val cue: WatchMapCue?,
)

@Serializable
data class WatchMapRoute(
    val version: Int = WatchMapProtocol.VERSION,
    val navigationSessionId: String,
    val revision: Long,
    val coordinates: DoubleArray,
)

@Serializable
data class WatchMapLeaseHighWater(
    val viewerId: String,
    val viewerEpoch: Long,
    val sequence: Long,
)

@Serializable
data class WatchMapLeaseNodeSnapshot(
    val nodeId: String,
    val currentViewerId: String,
    val maxViewerEpoch: Long,
    val highWater: List<WatchMapLeaseHighWater>,
    val retiredViewerIds: List<String>,
)

@Serializable
data class WatchMapLeasesSnapshot(
    val nodes: List<WatchMapLeaseNodeSnapshot>,
)

data class WatchMapSubscriber(
    val sourceNodeId: String,
    val viewerId: String,
    val viewerEpoch: Long,
    val presenceSequence: Long,
    val foreground: Boolean,
    val mapVisible: Boolean,
    val missingRoute: WatchMapRouteKey?,
    val missingTiles: List<WatchMapTileKey>,
    val lastAcceptedElapsedMs: Long,
)

class WatchMapLeases {
    private data class LeaseKey(val nodeId: String, val viewerId: String)
    private data class SequenceKey(val viewerId: String, val viewerEpoch: Long)
    private data class NodeHistory(
        var currentViewerId: String? = null,
        var maxViewerEpoch: Long = -1L,
        val highWater: MutableMap<SequenceKey, Long> = mutableMapOf(),
        val retiredViewerIds: MutableSet<String> = mutableSetOf(),
    )

    private val active = mutableMapOf<LeaseKey, WatchMapSubscriber>()
    private val history = mutableMapOf<String, NodeHistory>()

    fun accept(nodeId: String, presence: WatchMapPresence, nowMs: Long): Boolean {
        if (nodeId.isBlank() || !WatchMapProtocol.isValidPresence(presence)) return false
        expire(nowMs)

        val node = history.getOrPut(nodeId) { NodeHistory() }
        if (presence.viewerId in node.retiredViewerIds) return false

        val previousViewerId = node.currentViewerId
        if (previousViewerId == null) {
            node.currentViewerId = presence.viewerId
            node.maxViewerEpoch = presence.viewerEpoch
            node.highWater.clear()
        } else if (previousViewerId == presence.viewerId) {
            if (presence.viewerEpoch < node.maxViewerEpoch) return false
            if (presence.viewerEpoch > node.maxViewerEpoch) {
                node.maxViewerEpoch = presence.viewerEpoch
                node.highWater.clear()
            }
        } else {
            if (active.containsKey(LeaseKey(nodeId, previousViewerId))) return false
            node.retiredViewerIds += previousViewerId
            node.currentViewerId = presence.viewerId
            node.maxViewerEpoch = presence.viewerEpoch
            node.highWater.clear()
        }

        val sequenceKey = SequenceKey(presence.viewerId, presence.viewerEpoch)
        val lastSequence = node.highWater[sequenceKey]
        if (lastSequence != null && presence.sequence <= lastSequence) return false
        node.highWater[sequenceKey] = presence.sequence

        val leaseKey = LeaseKey(nodeId, presence.viewerId)
        if (presence.foreground) {
            active[leaseKey] = WatchMapSubscriber(
                sourceNodeId = nodeId,
                viewerId = presence.viewerId,
                viewerEpoch = presence.viewerEpoch,
                presenceSequence = presence.sequence,
                foreground = true,
                mapVisible = presence.mapVisible,
                missingRoute = presence.missingRoute,
                missingTiles = presence.missingTiles.toList(),
                lastAcceptedElapsedMs = nowMs,
            )
        } else {
            active.remove(leaseKey)
        }
        return true
    }

    fun expire(nowMs: Long) {
        active.entries.removeAll { (_, subscriber) ->
            nowMs - subscriber.lastAcceptedElapsedMs >= WatchMapProtocol.LEASE_MS
        }
    }

    fun hasVisibleMap(nowMs: Long): Boolean {
        expire(nowMs)
        return active.values.any { it.mapVisible }
    }

    fun subscribers(nowMs: Long): List<WatchMapSubscriber> {
        expire(nowMs)
        return active.values.toList()
    }

    fun clear() {
        active.clear()
    }

    fun snapshot(): WatchMapLeasesSnapshot = WatchMapLeasesSnapshot(
        nodes = history.entries.sortedBy { it.key }.mapNotNull { (nodeId, node) ->
            val currentViewerId = node.currentViewerId ?: return@mapNotNull null
            WatchMapLeaseNodeSnapshot(
                nodeId = nodeId,
                currentViewerId = currentViewerId,
                maxViewerEpoch = node.maxViewerEpoch,
                highWater = node.highWater.entries
                    .sortedWith(compareBy({ it.key.viewerId }, { it.key.viewerEpoch }))
                    .map { (key, sequence) ->
                        WatchMapLeaseHighWater(
                            viewerId = key.viewerId,
                            viewerEpoch = key.viewerEpoch,
                            sequence = sequence,
                        )
                    },
                retiredViewerIds = node.retiredViewerIds.sorted(),
            )
        },
    )

    fun restore(snapshot: WatchMapLeasesSnapshot) {
        active.clear()
        history.clear()
        snapshot.nodes.forEach { saved ->
            val valid = saved.nodeId.isNotBlank() &&
                saved.currentViewerId.isNotBlank() &&
                saved.maxViewerEpoch >= 0L &&
                saved.currentViewerId !in saved.retiredViewerIds &&
                saved.retiredViewerIds.all { it.isNotBlank() } &&
                saved.highWater.all {
                    it.viewerId.isNotBlank() &&
                        it.viewerEpoch in 0L..saved.maxViewerEpoch &&
                        it.sequence >= 0L
                }
            if (!valid || saved.nodeId in history) return@forEach

            val node = NodeHistory(
                currentViewerId = saved.currentViewerId,
                maxViewerEpoch = saved.maxViewerEpoch,
            )
            saved.highWater
                .asSequence()
                .filter { it.viewerId == saved.currentViewerId && it.viewerEpoch == saved.maxViewerEpoch }
                .forEach {
                    val key = SequenceKey(it.viewerId, it.viewerEpoch)
                    val old = node.highWater[key]
                    if (old == null || it.sequence > old) node.highWater[key] = it.sequence
                }
            node.retiredViewerIds += saved.retiredViewerIds
            history[saved.nodeId] = node
        }
    }
}

object WatchMapProtocol {
    const val VERSION = 1

    const val PRESENCE_PATH = "/euc/map/presence"
    const val FRAME_PATH = "/euc/map/frame"
    const val ROUTE_PATH = "/euc/map/route"
    const val TILE_PREFIX = "/euc/map/tile/"
    const val TILE_MESSAGE_PATH = "/euc/map/tile-fast"

    const val PRESENCE_INTERVAL_MS = 2_000L
    const val LEASE_MS = 6_000L
    const val LINK_STALE_MS = 3_000L

    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 19
    const val DEFAULT_ZOOM = 16
    const val MAX_TILE_REQUESTS = 25
    const val MAX_TILE_ASSET_BYTES = 4 * 1024 * 1024
    const val MAX_TILE_MESSAGE_BYTES = 96 * 1024
    private const val MAX_TILE_LAYER_ID_BYTES = 64
    private const val TILE_MESSAGE_HEADER_BYTES = 28
    const val ROUTE_VERSION_KEY = "version"
    const val ROUTE_SESSION_KEY = "navigationSessionId"
    const val ROUTE_REVISION_KEY = "revision"
    const val ROUTE_DELIVERY_GENERATION_KEY = "deliveryGeneration"
    const val ROUTE_GEOMETRY_KEY = "geometry"

    const val TILE_VERSION_KEY = "version"
    const val TILE_LAYER_KEY = "layerId"
    const val TILE_Z_KEY = "z"
    const val TILE_X_KEY = "x"
    const val TILE_Y_KEY = "y"
    const val TILE_DELIVERY_GENERATION_KEY = "deliveryGeneration"
    const val TILE_PNG_KEY = "png"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun isValidRoute(route: WatchMapRoute): Boolean {
        if (route.version != VERSION) return false
        if (route.navigationSessionId.isBlank() || route.revision <= 0L) return false
        if (route.coordinates.isEmpty()) return false
        if (route.coordinates.size % 2 != 0) return false
        for (index in route.coordinates.indices step 2) {
            val lat = route.coordinates[index]
            val lon = route.coordinates[index + 1]
            if (!lat.isFinite() || lat !in -90.0..90.0) return false
            if (!lon.isFinite() || lon !in -180.0..180.0) return false
        }
        return true
    }

    fun isValidFrame(frame: WatchMapFrame): Boolean {
        if (frame.version != VERSION) return false
        if (frame.phoneSessionId.isBlank() || frame.viewerId.isBlank()) return false
        if (frame.viewerEpoch < 0L || frame.presenceSequence < 0L || frame.sequence < 0L) return false
        if (frame.fixMaxAgeMs < 0L || frame.routeRevision < 0L) return false
        if (frame.layerId.isBlank()) return false
        if (frame.unavailableTiles.size > MAX_TILE_REQUESTS) return false
        if (!frame.unavailableTiles.all(::isValidTileKey)) return false
        if (frame.fix?.ageMs?.let { it < 0L } == true) return false
        if (frame.headingDeg?.isFinite() == false) return false
        if (frame.cue?.angleDeg?.isFinite() == false) return false
        if (frame.fix?.point?.let(::isValidPoint) == false) return false
        if (frame.anchor?.let(::isValidPoint) == false) return false
        if (frame.target?.let(::isValidPoint) == false) return false
        return true
    }

    fun isValidPresence(presence: WatchMapPresence): Boolean {
        if (presence.version != VERSION) return false
        if (presence.viewerId.isBlank()) return false
        if (presence.viewerEpoch < 0L || presence.sequence < 0L) return false
        if (presence.missingTiles.size > MAX_TILE_REQUESTS) return false
        if (presence.missingRoute?.let(::isValidRouteKey) == false) return false
        return presence.missingTiles.all(::isValidTileKey)
    }

    fun encodePresence(presence: WatchMapPresence): ByteArray? =
        if (isValidPresence(presence)) encode(presence) else null

    fun decodePresence(payload: ByteArray): WatchMapPresence? =
        decode<WatchMapPresence>(payload)?.takeIf(::isValidPresence)

    fun encodeFrame(frame: WatchMapFrame): ByteArray? =
        if (isValidFrame(frame)) encode(frame) else null

    fun decodeFrame(payload: ByteArray): WatchMapFrame? =
        decode<WatchMapFrame>(payload)?.takeIf(::isValidFrame)

    fun encodeRoute(route: WatchMapRoute): ByteArray? =
        if (isValidRoute(route)) encode(route) else null

    fun decodeRoute(payload: ByteArray): WatchMapRoute? =
        decode<WatchMapRoute>(payload)?.takeIf(::isValidRoute)

    fun encodeTileMessage(message: WatchMapTileMessage): ByteArray? {
        if (!isValidTileKey(message.key) ||
            message.deliveryGeneration < 0L ||
            message.encodedTile.isEmpty()
        ) {
            return null
        }
        val layerBytes = message.key.layerId.toByteArray(Charsets.UTF_8)
        if (layerBytes.isEmpty() || layerBytes.size > MAX_TILE_LAYER_ID_BYTES) return null
        if (message.encodedTile.size > MAX_TILE_MESSAGE_BYTES - TILE_MESSAGE_HEADER_BYTES - layerBytes.size) {
            return null
        }
        val totalBytes = TILE_MESSAGE_HEADER_BYTES + layerBytes.size + message.encodedTile.size
        return ByteBuffer.allocate(totalBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(VERSION)
            .putLong(message.deliveryGeneration)
            .putInt(message.key.z)
            .putInt(message.key.x)
            .putInt(message.key.y)
            .putInt(layerBytes.size)
            .put(layerBytes)
            .put(message.encodedTile)
            .array()
    }

    fun decodeTileMessage(payload: ByteArray): WatchMapTileMessage? {
        if (payload.size < TILE_MESSAGE_HEADER_BYTES || payload.size > MAX_TILE_MESSAGE_BYTES) {
            return null
        }
        return runCatching {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            if (buffer.int != VERSION) return@runCatching null
            val generation = buffer.long
            val z = buffer.int
            val x = buffer.int
            val y = buffer.int
            val layerLength = buffer.int
            if (generation < 0L ||
                layerLength <= 0 ||
                layerLength > MAX_TILE_LAYER_ID_BYTES ||
                layerLength > buffer.remaining() - 1
            ) {
                return@runCatching null
            }
            val layerBytes = ByteArray(layerLength)
            buffer.get(layerBytes)
            val layerId = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(layerBytes))
                .toString()
            val encodedTile = ByteArray(buffer.remaining())
            buffer.get(encodedTile)
            if (encodedTile.isEmpty()) return@runCatching null
            val key = WatchMapTileKey(layerId, z, x, y)
            if (!isValidTileKey(key)) return@runCatching null
            WatchMapTileMessage(key, generation, encodedTile)
        }.getOrNull()
    }

    private fun isValidRouteKey(key: WatchMapRouteKey): Boolean =
        key.navigationSessionId.isNotBlank() && key.revision > 0L

    private fun isValidTileKey(key: WatchMapTileKey): Boolean {
        if (key.layerId.isBlank() || key.z !in MIN_ZOOM..MAX_ZOOM) return false
        val side = 1 shl key.z
        return key.x in 0 until side && key.y in 0 until side
    }

    private fun isValidPoint(point: WatchMapPoint): Boolean =
        point.lat.isFinite() && point.lat in -90.0..90.0 &&
            point.lon.isFinite() && point.lon in -180.0..180.0

    private inline fun <reified T> encode(value: T): ByteArray? = runCatching {
        json.encodeToString(value).toByteArray(Charsets.UTF_8)
    }.getOrNull()

    private inline fun <reified T> decode(payload: ByteArray): T? = runCatching {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payload))
            .toString()
        json.decodeFromString<T>(text)
    }.getOrNull()
}
