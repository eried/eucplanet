package com.eried.eucplanet.hud.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TileZoomCapTest {

    @Test
    fun startsAtTheLayerCap() {
        assertEquals(19, TileZoomCap().cap(layerMax = 19))
    }

    @Test
    fun aNoDataTileLowersTheCapBelowThatZoom() {
        // Satellite in rural BC: real imagery to z18, Esri's
        // "Map data not yet available" tile at z19.
        val cap = TileZoomCap()
        cap.onNoData(19)
        assertEquals(18, cap.cap(layerMax = 19))
        // A deeper miss later never raises it back.
        cap.onNoData(19)
        assertEquals(18, cap.cap(layerMax = 19))
    }

    @Test
    fun theCapNeverDropsBelowTheMapsFloor() {
        val cap = TileZoomCap()
        cap.onNoData(2)
        assertEquals(TileZoomCap.MIN_ZOOM, cap.cap(layerMax = 19))
    }

    @Test
    fun aStyleSwapForgetsWhatTheOldProviderLacked() {
        val cap = TileZoomCap()
        cap.onNoData(17)
        cap.reset()
        assertEquals(19, cap.cap(layerMax = 19))
    }

    @Test
    fun onlyEsrisExactPlaceholderCountsAsNoData() {
        assertFalse(TileZoomCap.isNoDataTile(ByteArray(2521)))
        assertFalse(TileZoomCap.isNoDataTile(ByteArray(100)))
    }
}
