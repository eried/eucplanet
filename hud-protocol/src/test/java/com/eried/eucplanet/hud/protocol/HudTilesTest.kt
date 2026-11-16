package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HUD asks the phone for a layer's tiles; an older pair keeps the old way. */
class HudTilesTest {

    @Test fun `the HUD's request names the layer and the phone reads it back`() {
        val path = HudTiles.path(MapLayers.SATELLITE_LABELS, 15, 18109, 7425)
        assertEquals("/tiles/15/18109/7425.png?layer=satellite_labels", path)
        val layer = HudTiles.layerOf("GET $path HTTP/1.1")
        assertEquals(MapLayers.SATELLITE_LABELS, MapLayers.byId(layer!!).id)
    }

    @Test fun `a request with no layer is an older HUD`() {
        assertNull(HudTiles.layerOf("GET /tiles/15/18109/7425.png HTTP/1.1"))
        assertNull(HudTiles.layerOf("GET /tiles/15/18109/7425.png?layer= HTTP/1.1"))
        assertNull(HudTiles.layerOf("GET /tiles/15/1/2.png?layer=../../x HTTP/1.1"))
    }

    @Test fun `only a phone new enough decides the tiles`() {
        assertFalse(HudTiles.phoneDecides(HudTiles.PHONE_DECIDES_MINOR - 1))
        assertTrue(HudTiles.phoneDecides(HudTiles.PHONE_DECIDES_MINOR))
        assertTrue(HudTiles.phoneDecides(HudState.PROTOCOL_MINOR))
    }

    @Test fun `an ordinary picture is not the no-data tile`() {
        assertFalse(HudTiles.isNoDataTile(ByteArray(2521)))
        assertFalse(HudTiles.isNoDataTile(byteArrayOf(1, 2, 3)))
    }
}
