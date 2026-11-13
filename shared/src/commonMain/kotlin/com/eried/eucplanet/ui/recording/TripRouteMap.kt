package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eried.eucplanet.map.MapLayers
import com.eried.eucplanet.platform.format
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.*
import com.eried.eucplanet.ui.theme.appColors
import org.jetbrains.compose.resources.StringResource

/** A base map in the style menu: its layer id and its name. */
class TripMapLayerLabel(val id: String, val label: StringResource)

/**
 * The style menu, in the navigator's order and with its names
 * (RouteBuilderViewModel.MAP_LAYERS, which TripMapLayersTest holds it to).
 */
val TRIP_MAP_LAYER_LABELS = listOf(
    TripMapLayerLabel("OSM", Res.string.nav_layer_osm),
    TripMapLayerLabel("CYCLOSM", Res.string.nav_layer_cyclosm),
    TripMapLayerLabel("TOPO", Res.string.nav_layer_topo),
    TripMapLayerLabel("HUMANITARIAN", Res.string.nav_layer_humanitarian),
    TripMapLayerLabel("LIGHT", Res.string.nav_layer_light),
    TripMapLayerLabel("DARK", Res.string.nav_layer_dark),
    TripMapLayerLabel("SATELLITE", Res.string.nav_layer_satellite),
    TripMapLayerLabel("SATELLITE_LABELS", Res.string.nav_layer_satellite_labels),
)

// The rider's Trip-details map-style pick (LIGHT / DARK / SAT). Process-scoped so it
// applies instantly across trips this session without waiting on the settings flow;
// the pick is ALSO persisted (tripMapType) so it survives a restart. Null before the
// first pick, when the persisted value (or the theme default) seeds the map instead.
private var tripMapTypeSession: String? = null

@Composable
internal fun RouteMapView(
    points: List<TripDataPoint>,
    // The whole ride, drawn faded underneath when a trim is applied so the
    // section still reads in the context of the full trip. Empty = no trim.
    fadedPoints: List<TripDataPoint> = emptyList(),
    // Wheel-identity markers from the stretches the trim cut away, drawn faint
    // and without popups. Empty = no trim.
    fadedSwitches: List<WheelSwitchMarker> = emptyList(),
    // Whether the ride's real start / end survived the trim. Each endpoint marker
    // is ghosted only when its own end was the one cut away.
    startIncluded: Boolean = true,
    endIncluded: Boolean = true,
    isLive: Boolean = false,
    liveLat: Double? = null,
    liveLon: Double? = null,
    // When a chart is being scrubbed, the GPS position of that sample (or null to
    // hide the marker). Drives a dot on the map synced with the chart cursor.
    scrubLat: Double? = null,
    scrubLon: Double? = null,
    // Clock time and elapsed for the scrubbed sample, shown as a tooltip on the
    // dot. Empty = no tooltip.
    scrubLabel: String = "",
    // The compared trip's position at the scrubbed moment, or null to hide it.
    // A marker only, like eucviewer: B's whole route would crowd A's.
    compareLat: Double? = null,
    compareLon: Double? = null,
    // Wheel-identity markers after the ride start (a purple square and purple
    // trace onward for a different wheel, a small grey square for a reconnect)
    // and the start marker's popup text (the recording's first wheel identity,
    // empty = no popup).
    wheelSwitches: List<WheelSwitchMarker> = emptyList(),
    startLabel: String = "",
    endLabel: String = "",
    // Persisted base map pick (blank = none yet); a change is written back through
    // onPersistMapType so the style survives an app restart, not just the session.
    savedMapType: String = "",
    onPersistMapType: (String) -> Unit = {},
    host: TripDetailHost,
    modifier: Modifier = Modifier
) {
    // rememberSaveable so a rotation (which recreates the composition) keeps the
    // map fullscreen instead of dropping back to the trip details.
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Map style (light / dark / satellite) is shared between the inline and the
    // fullscreen map so opening fullscreen keeps the style the rider picked,
    // rather than resetting to light.
    // Priority: this-session pick > persisted pick > theme default. The theme
    // default reads the active background luminance (a dark theme, including a
    // custom dark one, gets the dark map; a light one gets the white map).
    val themeMapDefault = if (MaterialTheme.appColors.appBackground.luminance() < 0.5f) "DARK" else "LIGHT"
    // byId resolves the ids this screen used to persist (SAT) onto the shared
    // ones, so a rider who last chose satellite still gets satellite.
    var mapType by rememberSaveable {
        mutableStateOf(
            MapLayers
                .byId(tripMapTypeSession ?: savedMapType.ifBlank { themeMapDefault })
                .id
        )
    }
    // The persisted value is served through an Eagerly-started flow, so it is
    // normally present by first composition; guard the rare case where it arrives
    // after. Only adopt it while the rider has not picked this session.
    LaunchedEffect(savedMapType) {
        val resolved = MapLayers.byId(savedMapType).id
        if (tripMapTypeSession == null && savedMapType.isNotBlank() && resolved != mapType) {
            mapType = resolved
        }
    }
    val onPick: (String) -> Unit = { mapType = it; tripMapTypeSession = it; onPersistMapType(it) }

    MapSurface(
        points = points, fadedPoints = fadedPoints, fadedSwitches = fadedSwitches,
        startIncluded = startIncluded, endIncluded = endIncluded,
        isLive = isLive, liveLat = liveLat, liveLon = liveLon,
        scrubLat = scrubLat, scrubLon = scrubLon, scrubLabel = scrubLabel,
        compareLat = compareLat, compareLon = compareLon,
        wheelSwitches = wheelSwitches, startLabel = startLabel, endLabel = endLabel,
        fullscreen = false, onToggleFullscreen = { fullscreen = true },
        mapType = mapType, onMapTypeChange = onPick,
        host = host,
        modifier = modifier,
    )

    if (fullscreen) {
        // Fullscreen map: no parent scroll fights the gestures, so panning and
        // pinch-zoom are unencumbered. Back button or the exit icon closes it.
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            host.FullscreenMapWindow()
            MapSurface(
                points = points, fadedPoints = fadedPoints, fadedSwitches = fadedSwitches,
        startIncluded = startIncluded, endIncluded = endIncluded,
        isLive = isLive, liveLat = liveLat, liveLon = liveLon,
                scrubLat = scrubLat, scrubLon = scrubLon, scrubLabel = scrubLabel,
                compareLat = compareLat, compareLon = compareLon,
                wheelSwitches = wheelSwitches, startLabel = startLabel, endLabel = endLabel,
                fullscreen = true, onToggleFullscreen = { fullscreen = false },
                mapType = mapType, onMapTypeChange = onPick,
                host = host,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun MapSurface(
    points: List<TripDataPoint>,
    fadedPoints: List<TripDataPoint>,
    fadedSwitches: List<WheelSwitchMarker>,
    startIncluded: Boolean,
    endIncluded: Boolean,
    isLive: Boolean,
    liveLat: Double?,
    liveLon: Double?,
    scrubLat: Double?,
    scrubLon: Double?,
    scrubLabel: String,
    compareLat: Double?,
    compareLon: Double?,
    wheelSwitches: List<WheelSwitchMarker>,
    startLabel: String,
    endLabel: String,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    mapType: String,
    onMapTypeChange: (String) -> Unit,
    host: TripDetailHost,
    modifier: Modifier = Modifier,
) {
    val coordsJson = remember(points) { TripMapHtml.coordsJson(points) }
    // Empty unless a trim is applied, which makes the faded layer a no-op and
    // leaves the untrimmed map rendering exactly as it did before.
    val fadedCoordsJson = remember(fadedPoints) { TripMapHtml.coordsJson(fadedPoints) }
    // Wheel-change markers + start popup, JSON-encoded so rider-controlled
    // BLE names can never break out of the script block.
    val switchesJson = remember(wheelSwitches) { TripMapHtml.switchesJson(wheelSwitches) }
    // Same encoding as switchesJson. No idx: these never cut a trace colour.
    val fadedSwitchesJson = remember(fadedSwitches) { TripMapHtml.fadedSwitchesJson(fadedSwitches) }
    val startLabelJs = remember(startLabel) { TripMapHtml.quote(startLabel) }
    val endLabelJs = remember(endLabel) { TripMapHtml.quote(endLabel) }
    var page by remember { mutableStateOf<TripMapPage?>(null) }
    // The JS hooks only exist once the document has finished loading, so trace
    // pushes wait for this rather than being silently dropped.
    var pageReady by remember { mutableStateOf(false) }
    // Built ONCE, from the trace and style this surface first composed with:
    // it is only ever handed to the factory below, and every later change goes
    // through setTrace() and setMapType(). Baking the current style in means a
    // freshly-opened surface (e.g. fullscreen) does not flash light first.
    // Keying it on the trace would re-serialise twenty thousand coordinates
    // into a fresh string on every trim, for a document nothing loads.
    val html = remember {
        TripMapHtml.build(
            coordsJson, fadedCoordsJson, switchesJson, fadedSwitchesJson,
            startIncluded, endIncluded, startLabelJs, endLabelJs, isLive, mapType,
            offlineMapJs = host.offlineMapJs,
            leafletHead = host.leafletHead,
        )
    }

    Box(modifier.clip(RoundedCornerShape(if (fullscreen) 0.dp else 12.dp))) {
        host.MapWebView(
            html = html,
            onPage = { page = it },
            onLoaded = { pageReady = true },
            modifier = Modifier.fillMaxSize(),
        )
        // Controls: fullscreen toggle over the map-style cycler.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapButton(
                icon = if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                desc = "Fullscreen map",
                onClick = onToggleFullscreen,
            )
            Box {
                var layerMenu by remember { mutableStateOf(false) }
                MapButton(
                    icon = Icons.Default.Layers,
                    desc = stringResource(Res.string.nav_map_style),
                    onClick = { layerMenu = true },
                )
                DropdownMenu(
                    expanded = layerMenu,
                    onDismissRequest = { layerMenu = false },
                    containerColor = MaterialTheme.appColors.menuBackground
                ) {
                    TRIP_MAP_LAYER_LABELS.forEach { layer ->
                        val id = layer.id
                        DropdownMenuItem(
                            leadingIcon = {
                                RadioButton(
                                    selected = id == mapType,
                                    onClick = { onMapTypeChange(id); layerMenu = false }
                                )
                            },
                            text = { Text(stringResource(layer.label)) },
                            onClick = { onMapTypeChange(id); layerMenu = false }
                        )
                    }
                }
            }
        }
    }

    // Redraw the trace in place whenever a trim changes what is highlighted.
    // The camera is untouched: what the rider is looking at, and how far in,
    // is theirs to set.
    LaunchedEffect(
        coordsJson, fadedCoordsJson, switchesJson, fadedSwitchesJson,
        startIncluded, endIncluded, page, pageReady,
    ) {
        val wv = page ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        wv.evaluate(
            "if(window.setTrace)setTrace([$coordsJson],[$fadedCoordsJson]," +
                "$switchesJson,$fadedSwitchesJson,$startIncluded,$endIncluded);")
    }

    // Apply the shared style to this page whenever it changes (the initial
    // style is already baked into the HTML; this keeps both the inline and the
    // fullscreen surface in sync when either cycles it).
    LaunchedEffect(mapType, page) {
        page?.evaluate("if(window.setMapType)setMapType('$mapType');")
    }

    // Push live GPS updates into the map via a JS hook defined in the HTML.
    LaunchedEffect(isLive, liveLat, liveLon, page) {
        val wv = page ?: return@LaunchedEffect
        if (!isLive) return@LaunchedEffect
        val lat = liveLat ?: return@LaunchedEffect
        val lon = liveLon ?: return@LaunchedEffect
        wv.evaluate("if (window.updateLivePoint) updateLivePoint($lat,$lon);")
    }

    // Chart-scrub marker: move a dot to the scrubbed sample's GPS position, or
    // hide it when scrubbing stops (or the sample had no fix).
    LaunchedEffect(scrubLat, scrubLon, scrubLabel, page) {
        val wv = page ?: return@LaunchedEffect
        if (scrubLat != null && scrubLon != null) {
            // Quoted: the label is built from file-supplied timestamps, so it is
            // never dropped into the call unescaped.
            val tip = TripMapHtml.quote(scrubLabel)
            wv.evaluate(
                "if(window.updateScrubPoint)updateScrubPoint($scrubLat,$scrubLon,$tip);")
        } else {
            wv.evaluate("if(window.clearScrubPoint)clearScrubPoint();")
        }
    }

    // The compared trip's marker, in its theme color, at the same moment.
    val compareCss = MaterialTheme.appColors.chartCompare.let {
        "#%06X".format(it.toArgb() and 0xFFFFFF)
    }
    LaunchedEffect(compareLat, compareLon, compareCss, page) {
        val wv = page ?: return@LaunchedEffect
        if (compareLat != null && compareLon != null) {
            wv.evaluate(
                "if(window.updateComparePoint)updateComparePoint($compareLat,$compareLon,'$compareCss');")
        } else {
            wv.evaluate("if(window.clearComparePoint)clearComparePoint();")
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurface)
    }
}
