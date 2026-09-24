package com.eried.eucplanet.hud.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearMapProtocolTest {

    @Test
    fun routeCodecRejectsTheWholeInvalidPayload() {
        val valid = WatchMapRoute(
            navigationSessionId = "nav-1",
            revision = 1L,
            coordinates = doubleArrayOf(90.0, -180.0, -90.0, 180.0),
        )
        assertNotNull(WatchMapProtocol.decodeRoute(WatchMapProtocol.encodeRoute(valid)!!))

        assertNull(WatchMapProtocol.encodeRoute(valid.copy(revision = 0L)))
        assertNull(WatchMapProtocol.encodeRoute(valid.copy(coordinates = doubleArrayOf())))
        assertNull(WatchMapProtocol.encodeRoute(valid.copy(coordinates = doubleArrayOf(1.0))))
        assertNull(
            WatchMapProtocol.encodeRoute(
                valid.copy(coordinates = doubleArrayOf(90.0001, 0.0)),
            ),
        )
        assertNull(
            WatchMapProtocol.encodeRoute(
                valid.copy(coordinates = doubleArrayOf(0.0, Double.NaN)),
            ),
        )
    }

    @Test
    fun presenceCodecEnforcesIdentityAndTileBounds() {
        val valid = WatchMapPresence(
            viewerId = "viewer-1",
            viewerEpoch = 0L,
            sequence = 0L,
            foreground = true,
            mapVisible = true,
            missingRoute = WatchMapRouteKey("nav-1", 1L),
            missingTiles = listOf(WatchMapTileKey("OSM", 3, 7, 7)),
        )
        assertNotNull(WatchMapProtocol.decodePresence(WatchMapProtocol.encodePresence(valid)!!))

        assertNull(WatchMapProtocol.encodePresence(valid.copy(viewerId = "")))
        assertNull(WatchMapProtocol.encodePresence(valid.copy(viewerEpoch = -1L)))
        assertNull(
            WatchMapProtocol.encodePresence(
                valid.copy(missingTiles = List(WatchMapProtocol.MAX_TILE_REQUESTS + 1) { valid.missingTiles.single() }),
            ),
        )
        assertNull(
            WatchMapProtocol.encodePresence(
                valid.copy(missingTiles = listOf(WatchMapTileKey("OSM", 3, 8, 0))),
            ),
        )
    }

    @Test
    fun frameCodecKeepsNullsAndIgnoresFutureFields() {
        val frame = WatchMapFrame(
            phoneSessionId = "phone-1",
            viewerId = "viewer-1",
            viewerEpoch = 1L,
            presenceSequence = 2L,
            sequence = 3L,
            enabled = true,
            headingUp = false,
            layerId = "OSM",
            unavailableTiles = emptyList(),
            locationStatus = WatchMapLocationStatus.WAITING_FIX,
            fix = null,
            anchor = null,
            fixMaxAgeMs = 10_000L,
            headingDeg = null,
            navigationSessionId = "",
            navigationActive = false,
            routeRevision = 0L,
            target = null,
            cue = null,
        )
        val encoded = WatchMapProtocol.encodeFrame(frame)!!
        val json = encoded.toString(Charsets.UTF_8)
        assertTrue(json.contains("\"fix\":null"))
        assertTrue(json.contains("\"headingDeg\":null"))

        val withFutureField = json.dropLast(1) + ",\"future\":true}"
        assertNotNull(WatchMapProtocol.decodeFrame(withFutureField.toByteArray(Charsets.UTF_8)))
        assertNull(WatchMapProtocol.encodeFrame(frame.copy(phoneSessionId = "")))
        assertNull(WatchMapProtocol.encodeFrame(frame.copy(fixMaxAgeMs = -1L)))
        assertNull(
            WatchMapProtocol.encodeFrame(
                frame.copy(unavailableTiles = listOf(WatchMapTileKey("OSM", 3, 8, 0))),
            ),
        )
        assertNull(
            WatchMapProtocol.encodeFrame(
                frame.copy(fix = WatchMapFix(WatchMapPoint(0.0, 0.0), -1L)),
            ),
        )
        assertNull(WatchMapProtocol.encodeFrame(frame.copy(headingDeg = Float.NaN)))
        assertNull(
            WatchMapProtocol.encodeFrame(
                frame.copy(cue = WatchMapCue(Float.NaN, "", "", false)),
            ),
        )
        assertNull(
            WatchMapProtocol.encodeFrame(
                frame.copy(fix = WatchMapFix(WatchMapPoint(90.0001, 0.0), 0L)),
            ),
        )
        val invalidTarget = frame.copy(target = WatchMapPoint(0.0, 180.0001))
        val invalidTargetJson = WatchMapProtocol.json
            .encodeToString(WatchMapFrame.serializer(), invalidTarget)
            .toByteArray(Charsets.UTF_8)
        assertNull(WatchMapProtocol.decodeFrame(invalidTargetJson))
        assertNull(WatchMapProtocol.decodeFrame(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun tileMessageCodecRoundTripsExactBinaryPayload() {
        val message = WatchMapTileMessage(
            key = WatchMapTileKey("OSM", 3, 1, 2),
            deliveryGeneration = 7L,
            encodedTile = byteArrayOf(1, 2, 3, 0),
        )

        val encoded = WatchMapProtocol.encodeTileMessage(message)
        val decoded = encoded?.let(WatchMapProtocol::decodeTileMessage)

        assertNotNull(encoded)
        assertNotNull(decoded)
        assertTrue(decoded!!.key == message.key)
        assertTrue(decoded.deliveryGeneration == message.deliveryGeneration)
        assertArrayEquals(message.encodedTile, decoded.encodedTile)
    }

    @Test
    fun tileMessageCodecRejectsInvalidEncodeBoundaries() {
        val valid = WatchMapTileMessage(
            key = WatchMapTileKey("OSM", 3, 1, 2),
            deliveryGeneration = 0L,
            encodedTile = byteArrayOf(1),
        )
        assertNull(WatchMapProtocol.encodeTileMessage(valid.copy(encodedTile = byteArrayOf())))
        assertNull(WatchMapProtocol.encodeTileMessage(valid.copy(deliveryGeneration = -1L)))
        assertNull(WatchMapProtocol.encodeTileMessage(valid.copy(key = WatchMapTileKey("", 3, 1, 2))))
        assertNull(WatchMapProtocol.encodeTileMessage(valid.copy(key = WatchMapTileKey("OSM", 3, 8, 0))))
        assertNull(WatchMapProtocol.encodeTileMessage(valid.copy(key = WatchMapTileKey("é".repeat(65), 3, 1, 2))))
        assertNull(
            WatchMapProtocol.encodeTileMessage(
                valid.copy(encodedTile = ByteArray(WatchMapProtocol.MAX_TILE_MESSAGE_BYTES)),
            ),
        )
    }

    @Test
    fun tileMessageCodecRejectsMalformedAndUntrustedDecodeInput() {
        val valid = WatchMapProtocol.encodeTileMessage(
            WatchMapTileMessage(WatchMapTileKey("OSM", 3, 1, 2), 1L, byteArrayOf(9)),
        )!!

        assertNull(WatchMapProtocol.decodeTileMessage(ByteArray(27)))
        assertNull(WatchMapProtocol.decodeTileMessage(ByteArray(WatchMapProtocol.MAX_TILE_MESSAGE_BYTES + 1)))
        assertNull(WatchMapProtocol.decodeTileMessage(valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(0, 2)
        }))
        assertNull(WatchMapProtocol.decodeTileMessage(valid.copyOfRange(0, 29)))
        assertNull(WatchMapProtocol.decodeTileMessage(valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(24, 65)
        }))
        assertNull(WatchMapProtocol.decodeTileMessage(valid.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putLong(4, -1L)
        }))

        val invalidUtf8 = ByteBuffer.allocate(30)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(WatchMapProtocol.VERSION)
            .putLong(0L)
            .putInt(3)
            .putInt(1)
            .putInt(2)
            .putInt(1)
            .put(0xC3.toByte())
            .put(1)
            .array()
        assertNull(WatchMapProtocol.decodeTileMessage(invalidUtf8))
    }
}
