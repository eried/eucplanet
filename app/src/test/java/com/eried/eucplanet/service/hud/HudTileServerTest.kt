package com.eried.eucplanet.service.hud

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HUD gets offline map pictures from the phone over plain HTTP. */
class HudTileServerTest {

    @Test fun onlyTileRequestsParse() {
        assertEquals(Triple(15, 18109, 7425), HudTileServer.parse("GET /tiles/15/18109/7425.png HTTP/1.1"))
        assertNull(HudTileServer.parse("GET /tiles/15/18109/7425.png/../../x HTTP/1.1"))
        assertNull(HudTileServer.parse("POST /tiles/1/0/0.png HTTP/1.1"))
        assertNull("x past the edge", HudTileServer.parse("GET /tiles/1/2/0.png HTTP/1.1"))
        assertNull(HudTileServer.parse("GET /health HTTP/1.1"))
    }

    @Test fun servesATile_andA404WhereThereIsNone() {
        val png = byteArrayOf(1, 2, 3)
        val server = HudTileServer(0, { z, _, _ -> if (z == 15) png else null })
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
}
