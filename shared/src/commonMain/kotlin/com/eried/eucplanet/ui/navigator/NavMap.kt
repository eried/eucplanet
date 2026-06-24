package com.eried.eucplanet.ui.navigator

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Imperative native→JS command surface for the route-builder Leaflet map,
 * mirroring the `window.native*` functions defined in [MapHtml]. The actual
 * [NavMapView] wires [sink] to the platform WebView's evaluateJavaScript.
 */
class NavMapController {
    internal var sink: ((String) -> Unit)? = null
    private fun js(code: String) { sink?.invoke(code) }

    // nativeRender / nativeSetPlaces JSON.parse their first args, so the JSON must be
    // passed as quoted JS strings — NOT raw literals (raw arrays make JSON.parse throw,
    // which silently killed all marker/route drawing).
    fun render(wpJson: String, geomJson: String, fit: Boolean, pendingJson: String) =
        js("window.nativeRender(${jsStr(wpJson)},${jsStr(geomJson)},$fit,${jsStr(pendingJson)})")
    fun setUser(lat: Double, lng: Double) = js("window.nativeSetUser($lat,$lng)")
    fun setUserHeading(deg: Double) = js("window.nativeSetUserHeading($deg)")
    fun setUserStill() = js("window.nativeSetUserStill()")
    fun setUserPhoto(dataUrl: String) = js("window.nativeSetUserPhoto(${jsStr(dataUrl)})")
    fun recenter(lat: Double, lng: Double, zoom: Double, bottomOffsetPx: Int = 0) =
        js("window.nativeRecenter($lat,$lng,$zoom,$bottomOffsetPx)")
    fun centerOn(lat: Double, lng: Double, bottomOffsetPx: Int = 0) =
        js("window.nativeCenterOn($lat,$lng,$bottomOffsetPx)")
    fun setAccent(hex: String) = js("window.nativeSetAccent(${jsStr(hex)})")
    fun setRouteColors(walk: String, bike: String, drive: String, straight: String, preview: String) =
        js("window.nativeSetRouteColors(${jsStr(walk)},${jsStr(bike)},${jsStr(drive)},${jsStr(straight)},${jsStr(preview)})")
    fun setMapType(type: String) = js("window.nativeSetMapType(${jsStr(type)})")
    fun setPlaces(json: String) = js("window.nativeSetPlaces(${jsStr(json)})")
    fun setNavLocked(locked: Boolean) = js("window.nativeSetNavLocked($locked)")
    fun setTravelMode(mode: String) = js("window.nativeSetTravelMode(${jsStr(mode)})")
    fun setFullPath(b: Boolean) = js("window.nativeSetFullPath($b)")
    fun setRecenterOffset(px: Int) = js("window.nativeSetRecenterOffset($px)")

    private fun jsStr(s: String) =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'"
}

/** JS→native callbacks the map fires, matching Android's `AndroidNav` interface. */
class NavMapCallbacks(
    val onMapClick: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    val onRouteLineClick: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    val onMarkerDragged: (index: Int, lat: Double, lng: Double) -> Unit = { _, _, _ -> },
    val onMarkerDragStart: () -> Unit = {},
    val onSelfTap: (x: Int, y: Int) -> Unit = { _, _ -> },
    val onMarkerTapped: (index: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
    val onMapViewChanged: (lat: Double, lng: Double, zoom: Float) -> Unit = { _, _, _ -> },
    val onTilesLoaded: () -> Unit = {},
)

/**
 * The Leaflet route-builder map (full Android parity). iOS hosts a WKWebView and
 * injects an `AndroidNav` shim over a WKScriptMessageHandler; the shared Android
 * map is a placeholder (the Android app uses its own navigator).
 */
@Composable
expect fun NavMapView(
    mapType: String,
    controller: NavMapController,
    callbacks: NavMapCallbacks,
    modifier: Modifier,
)
