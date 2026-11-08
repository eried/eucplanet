package com.eried.eucplanet.ui.recording

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The trip map's loaded page, for the screen's calls into its JS. */
fun interface TripMapPage {
    fun evaluate(js: String)
}

/** What the trip viewer asks of the platform it is drawn on. */
interface TripDetailHost {
    /** A debug build: the tile registry's drift guard throws on a mismatch. */
    val isDebugBuild: Boolean

    /** The map page's Leaflet tags: [TripMapHtml.LEAFLET_CDN_HEAD], or a bundled copy inlined. */
    val leafletHead: String

    /** Defines eucOfflineMap(map) on the page: the downloaded map, or [TripMapHtml.NO_OFFLINE_MAP_JS]. */
    val offlineMapJs: String

    /**
     * The web view the route map draws in. It loads [html] once; [onPage]
     * gets the page to call into and [onLoaded] runs when the document has
     * loaded. A drag on it pans the map rather than scrolling the screen.
     */
    @Composable
    fun MapWebView(html: String, onPage: (TripMapPage) -> Unit, onLoaded: () -> Unit, modifier: Modifier)

    /** Inside the fullscreen map's dialog: makes its window edge to edge, system bars hidden. */
    @Composable
    fun FullscreenMapWindow()
}
