package com.eried.eucplanet.ui.navigator

import com.eried.eucplanet.ui.recording.TripMapHtml
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The route builder's page is one shared Leaflet page, filled in for the rider's map. */
class MapHtmlTest {

    @Test fun thePage_isFilledInForTheSavedMapAndTheOfflineScript() {
        val html = routeBuilderHtmlFor("SATELLITE", "window.eucOfflineMap = function() {};")
        assertFalse(html.contains("__INITIAL_MAP_TYPE__"))
        assertFalse(html.contains("__INITIAL_BG__"))
        assertFalse(html.contains("__LAYERS_JSON__"))
        assertFalse(html.contains("__OFFLINE_JS__"))
        assertTrue(html.contains("window.eucOfflineMap = function() {};"))
        assertTrue(html.contains(mapTypeInitialBg("SATELLITE")))
    }

    @Test fun aPhoneWithoutTheOfflineMap_getsTheEmptyScript() {
        val html = routeBuilderHtmlFor("OSM", TripMapHtml.NO_OFFLINE_MAP_JS)
        assertTrue(html.contains(TripMapHtml.NO_OFFLINE_MAP_JS))
    }

    @Test fun eachMapStyle_paintsItsOwnBackgroundFirst() {
        assertTrue(mapTypeInitialBg("LIGHT") != mapTypeInitialBg("DARK"))
        assertTrue(mapTypeInitialBg("SATELLITE_LABELS") == mapTypeInitialBg("SATELLITE"))
    }
}
