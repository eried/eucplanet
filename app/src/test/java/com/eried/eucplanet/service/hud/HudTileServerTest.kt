package com.eried.eucplanet.service.hud

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD gets offline map pictures from the phone over plain HTTP. Which
 * requests are tiles is HudTileHttpTest's, in shared code.
 */
class HudTileServerTest {

    @Test fun servesATile_andA404WhereThereIsNone() {
        val png = byteArrayOf(1, 2, 3)
        val server = HudTileServer(0, { z, _, _, _ -> if (z == 15) png else null })
        assertTrue(server.start())
        try {
            val ok = URL("http://127.0.0.1:${server.boundPort}/tiles/15/1/2.png").openConnection() as HttpURLConnection
            assertEquals(200, ok.responseCode)
            assertEquals("image/png", ok.contentType)
            assertArrayEquals(png, ok.inputStream.readBytes())
            val none = URL("http://127.0.0.1:${server.boundPort}/tiles/3/1/2.png").openConnection() as HttpURLConnection
            assertEquals(404, none.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test fun aLayerRequestGetsThatLayer_andA204WhereItHasNothingThatDeep() {
        val server = HudTileServer(0, { z, _, _, layer ->
            when {
                layer == null -> byteArrayOf(9)
                z == 19 -> ByteArray(0)
                else -> layer.toByteArray()
            }
        })
        assertTrue(server.start())
        try {
            val base = "http://127.0.0.1:${server.boundPort}"
            val path = com.eried.eucplanet.hud.protocol.HudTiles.path("SATELLITE", 15, 1, 2)
            val ok = URL(base + path).openConnection() as HttpURLConnection
            assertEquals(200, ok.responseCode)
            assertArrayEquals("satellite".toByteArray(), ok.inputStream.readBytes())
            val deep = URL(base + com.eried.eucplanet.hud.protocol.HudTiles.path("SATELLITE", 19, 1, 2))
                .openConnection() as HttpURLConnection
            assertEquals(com.eried.eucplanet.hud.protocol.HudTiles.NO_DATA_STATUS, deep.responseCode)
            val old = URL("$base/tiles/15/1/2.png").openConnection() as HttpURLConnection
            assertArrayEquals("an older HUD names no layer", byteArrayOf(9), old.inputStream.readBytes())
        } finally {
            server.stop()
        }
    }
}
