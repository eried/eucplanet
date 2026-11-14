package com.eried.eucplanet.ui.navigator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The route builder's loaded map page, for the screen's calls into its JS.
 * [resultCallback] runs on the main thread once the script has run.
 */
fun interface RouteMapPage {
    fun evaluateJavascript(script: String, resultCallback: ((String?) -> Unit)?)
}

/**
 * What the route builder's map page calls back with. The page talks to the
 * object named `AndroidNav`; the host delivers each call on the main thread,
 * one at a time, so two quick taps cannot race a waypoint update.
 */
class RouteMapBridge(
    val mapClick: (Double, Double) -> Unit,
    val routeLineClick: (Double, Double) -> Unit,
    val markerDragged: (Int, Double, Double) -> Unit,
    val markerDragStart: () -> Unit,
    val markerDragEnd: () -> Unit,
    val selfTap: (Int, Int) -> Unit,
    val markerTapped: (Int, Int, Int) -> Unit,
    val mapViewChanged: (Double, Double, Float) -> Unit,
    val mapBoundsChanged: (Double, Double, Double, Double) -> Unit,
    val tilesLoaded: () -> Unit,
    val poiTapped: (Long) -> Unit,
    val bearingChanged: (Float) -> Unit,
    val offlineMap: (Boolean) -> Unit,
)

/**
 * What the route builder, its share dialogs and the navigator view ask of the
 * platform they are drawn on: the web view the map lives in, the pickers, the
 * camera, the share sheet and the clipboard. Android's is built in the
 * composition (rememberAndroidRouteBuilderHost); iOS has none yet, the
 * navigator is muted there.
 */
interface RouteBuilderHost {
    /** Defines eucOfflineMap(map) on the page: the downloaded map, or TripMapHtml.NO_OFFLINE_MAP_JS. */
    val offlineMapJs: String

    /** The largest QR bitmap the share dialogs draw, in pixels. */
    val qrMaxPx: Int

    /**
     * The web view the map draws in. It loads [html] once, painted
     * [backgroundArgb] until the tiles arrive, wires [bridge] to the page,
     * hands the page to [onPage] and calls [onPageFinished] when it has
     * loaded. Leaving the composition tears it down.
     */
    @Composable
    fun MapView(
        html: String,
        backgroundArgb: Int,
        bridge: RouteMapBridge,
        onPage: (RouteMapPage) -> Unit,
        onPageFinished: () -> Unit,
        modifier: Modifier,
    )

    /** The system back gesture or button, while the builder shows. */
    @Composable
    fun BackHandler(onBack: () -> Unit)

    /** Keeps the screen on while [on], on top of the app's own keep-on setting. */
    @Composable
    fun KeepScreenOn(on: Boolean)

    /** Asks for the location permission once, when it is not granted yet. */
    @Composable
    fun RequestLocationPermission()

    /** The "save as" picker for a GPX file; the returned function opens it with a suggested name. */
    @Composable
    fun rememberGpxSaver(onPicked: (file: String) -> Unit): (suggestedName: String) -> Unit

    /** The "open" picker for a GPX file; [onPicked] gets the picked file's handle. */
    @Composable
    fun rememberGpxOpener(onPicked: (file: String) -> Unit): () -> Unit

    /**
     * The photo picker for the rider's marker with its crop step, drawn by the
     * host. [onPicked] gets the cropped photo as a `data:image/png;base64,` URL.
     */
    @Composable
    fun rememberMarkerPhotoPicker(onPicked: (dataUrl: String) -> Unit): () -> Unit

    /** Opens [url] in the browser; quietly does nothing when nothing can. */
    fun openUrl(url: String)

    /** A picture from the web (an Open Charge Map photo), cropped to fill [modifier]. */
    @Composable
    fun RemoteImage(url: String, modifier: Modifier)

    /** The offline areas dialog, opened on [routeSquares] the ride needs. */
    @Composable
    fun OfflineDataDialog(routeSquares: List<String>, onDismiss: () -> Unit)

    /** [content] as a QR code [sizePx] wide; [mediumEcc] for the denser route links. */
    @Composable
    fun QrCode(content: String, sizePx: Int, modifier: Modifier, mediumEcc: Boolean)

    /** The live camera square that reads a code [parse] accepts. */
    @Composable
    fun <T : Any> QrScanner(
        parse: (String) -> T?,
        onFound: (T) -> Unit,
        invalidText: String,
        modifier: Modifier,
        misfit: (String) -> String?,
    )

    /**
     * Puts [text] on the clipboard. [onCopied] runs when the phone does not
     * confirm a copy itself, so the rider is told exactly once.
     */
    fun copyText(text: String, onCopied: () -> Unit)

    /** Hands [text] to the share sheet. */
    fun shareText(text: String, subject: String)

    /**
     * Opens a share or route link in the phone's real browser rather than
     * back in this app, with the tab coloured [toolbarArgb] / [navigationBarArgb].
     */
    fun openShareLink(url: String, toolbarArgb: Int, navigationBarArgb: Int)

    /** Shares the route's QR as a picture card with [title], [distance] and [footer] on it. */
    fun shareRouteQrImage(link: String, text: String, title: String, distance: String, footer: String, subject: String)
}

/**
 * The host the route builder screen was given, for the dialogs and sheets it
 * opens (the share windows, the charger details), so each need not be handed
 * it by name.
 */
val LocalRouteBuilderHost = androidx.compose.runtime.staticCompositionLocalOf<RouteBuilderHost> {
    error("No RouteBuilderHost: the route builder provides one")
}
