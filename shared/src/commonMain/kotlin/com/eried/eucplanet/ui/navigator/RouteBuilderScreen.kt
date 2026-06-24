package com.eried.eucplanet.ui.navigator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eried.eucplanet.data.createFileStore
import com.eried.eucplanet.data.model.NavMode
import com.eried.eucplanet.data.model.TravelMode
import com.eried.eucplanet.nav.NavFormat
import com.eried.eucplanet.ui.theme.appColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The route planner — faithful parity with Android's RouteBuilderScreen: a live
 * Leaflet map (tap to add a stop, drag a pin to move it, tap a leg to insert a
 * detour), address search, a travel-mode selector, a stops panel with the tour
 * distance, recenter / map-style controls, and Start / Stop navigation.
 */
@Composable
fun RouteBuilderScreen(vm: RouteBuilderViewModel, onBack: () -> Unit) {
    val c = MaterialTheme.appColors
    val controller = remember { NavMapController() }

    val waypoints by vm.waypoints.collectAsState()
    val mapType by vm.mapType.collectAsState()
    val mapRender by vm.mapRender.collectAsState()
    val loc by vm.currentLocation.collectAsState()
    val travelMode by vm.travelMode.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val routing by vm.routing.collectAsState()
    val tourDist by vm.tourDistanceM.collectAsState()
    val navRunning by vm.navRunning.collectAsState()
    val imperial by vm.imperialUnits.collectAsState()
    val home by vm.home.collectAsState()
    val work by vm.work.collectAsState()

    var mapReady by remember { mutableStateOf(false) }
    // Safety net: the map gates all marker/route drawing on the tile-load event, but in
    // WKWebView (CDN tiles) that event can miss — unblock rendering after a few seconds so
    // tapping a stop still draws it. No-op once onTilesLoaded has already fired.
    LaunchedEffect(Unit) { delay(3500); mapReady = true }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    val fileStore = remember { createFileStore() }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { toast = it } }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

    val callbacks = remember {
        NavMapCallbacks(
            onMapClick = { lat, lng -> vm.addWaypoint(lat, lng) },
            onRouteLineClick = { lat, lng -> vm.insertWaypointOnRoute(lat, lng) },
            onMarkerDragStart = { vm.setUserDragging(true) },
            onMarkerDragged = { i, lat, lng -> vm.moveWaypoint(i, lat, lng); vm.setUserDragging(false) },
            onSelfTap = { _, _ -> vm.notifyTapOnSelf() },
            onMarkerTapped = { _, _, _ -> },
            onMapViewChanged = { lat, lng, z -> vm.setSavedView(lat, lng, z) },
            onTilesLoaded = { mapReady = true },
        )
    }

    // Redraw the map whenever the VM bumps its render version.
    LaunchedEffect(mapReady, mapRender) {
        if (!mapReady) return@LaunchedEffect
        controller.setAccent(c.primary.toHex6())
        controller.setMapType(mapType)
        controller.setTravelMode(travelMode.name)
        controller.setNavLocked(navRunning)
        controller.setPlaces(vm.placesJson())
        controller.render(vm.waypointsJson(), vm.geometryJson(), mapRender.fit, vm.pendingPreviewJson())
    }
    // First frame: centre on the saved view or the rider.
    LaunchedEffect(mapReady) {
        if (!mapReady) return@LaunchedEffect
        val sv = vm.savedView.value
        val l = vm.currentLocation.value
        when {
            sv != null -> controller.recenter(sv.lat, sv.lng, sv.zoom.toDouble())
            l != null -> controller.recenter(l.lat, l.lng, 15.0)
        }
    }
    LaunchedEffect(mapReady, loc) { if (mapReady && loc != null) controller.setUser(loc!!.lat, loc!!.lng) }
    LaunchedEffect(mapReady, mapType) { if (mapReady) controller.setMapType(mapType) }
    LaunchedEffect(mapReady, navRunning) { if (mapReady) controller.setNavLocked(navRunning) }
    LaunchedEffect(query) { vm.search(query) }

    Box(Modifier.fillMaxSize().background(c.appBackground)) {
        NavMapView(mapType = mapType, controller = controller, callbacks = callbacks, modifier = Modifier.fillMaxSize())

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // Top: back + search.
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.Filled.Navigation, c.dialog, c.textPrimary) { onBack() }
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                    placeholder = { Text("Search a place", fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.textSecondary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = c.dialog,
                        unfocusedContainerColor = c.dialog,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Box {
                    RoundIcon(Icons.Filled.MoreVert, c.dialog, c.textPrimary) { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (waypoints.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Clear route") }, onClick = { menuOpen = false; vm.clear() })
                            DropdownMenuItem(text = { Text("Save GPX") }, onClick = {
                                menuOpen = false
                                val path = fileStore.writeText("route.gpx", vm.saveGpx())
                                toast = if (path != null) "Saved route.gpx to Documents" else "Couldn't save the route"
                            })
                        }
                        home?.let { h -> DropdownMenuItem(text = { Text("Add Home") }, onClick = { menuOpen = false; vm.addPreset(h, "HOME") }) }
                        work?.let { w -> DropdownMenuItem(text = { Text("Add Work") }, onClick = { menuOpen = false; vm.addPreset(w, "WORK") }) }
                        DropdownMenuItem(text = { Text(if (home == null) "Save my location as Home" else "Replace Home") }, onClick = { menuOpen = false; vm.saveSelfAsHome() })
                        DropdownMenuItem(text = { Text(if (work == null) "Save my location as Work" else "Replace Work") }, onClick = { menuOpen = false; vm.saveSelfAsWork() })
                    }
                }
            }
            // Search results dropdown.
            if (searchResults.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(12.dp)).background(c.dialog),
                ) {
                    searchResults.take(6).forEach { r ->
                        Text(
                            r.name, color = c.textPrimary, fontSize = 13.sp, maxLines = 2,
                            modifier = Modifier.fillMaxWidth().clickable {
                                query = ""; vm.pickSearchResult(r)
                            }.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Right-side FABs (recenter, map style).
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundIcon(Icons.Filled.Layers, c.dialog, c.textPrimary) { vm.cycleMapType() }
                    RoundIcon(Icons.Filled.MyLocation, c.dialog, c.primary) {
                        vm.recenterOnUser()?.let { controller.recenter(it.lat, it.lng, 16.0) }
                    }
                }
            }
            Spacer(Modifier.size(10.dp))

            // Bottom planner panel.
            Column(
                Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(18.dp)).background(c.dialog).padding(14.dp),
            ) {
                // Travel modes — Android order: Direct, Bike, Walk, Car.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(TravelMode.STRAIGHT, TravelMode.CYCLING, TravelMode.WALKING, TravelMode.DRIVING).forEach { m ->
                        val sel = m == travelMode
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (sel) c.primary else c.surface)
                                .clickable { vm.setTravelMode(m) }.padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(modeIcon(m), contentDescription = modeLabel(m), tint = if (sel) c.onPrimary else c.textSecondary, modifier = Modifier.size(19.dp))
                                Text(
                                    modeLabel(m), color = if (sel) c.onPrimary else c.textSecondary,
                                    fontSize = 10.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.size(10.dp))

                // Distance + stop count.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            routing -> "Routing…"
                            tourDist != null -> NavFormat.distance(tourDist!!, imperial)
                            waypoints.isEmpty() -> "Tap the map to add stops"
                            else -> "${waypoints.size} stop${if (waypoints.size == 1) "" else "s"}"
                        },
                        color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (waypoints.isNotEmpty()) {
                        RoundIcon(Icons.Filled.Delete, c.surface, c.statusDanger, size = 38) { vm.clear() }
                    }
                }

                // Stops list.
                if (waypoints.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                        items(waypoints) { w ->
                            val idx = waypoints.indexOf(w)
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape).background(if (w.passed) c.textDisabled else c.primary),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${idx + 1}", color = c.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    w.name.ifBlank { "Stop ${idx + 1}" }, color = c.textPrimary, fontSize = 12.sp,
                                    maxLines = 1, modifier = Modifier.weight(1f),
                                )
                                Text("✕", color = c.textDisabled, fontSize = 14.sp, modifier = Modifier.clickable { vm.removeWaypoint(idx) }.padding(6.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.size(10.dp))
                // Start / Stop navigation.
                val canStart = waypoints.isNotEmpty()
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (navRunning) c.statusDanger else if (canStart) c.primary else c.surface)
                        .clickable(enabled = navRunning || canStart) {
                            if (navRunning) vm.stopNavigation()
                            // Android pops back to the dashboard on start, where the
                            // turn-by-turn overlay takes over (return here via the map button).
                            else vm.startNavigation(NavMode.TURN_BY_TURN) { onBack() }
                        }.padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (navRunning) "Stop navigation" else "Start navigation",
                        color = if (navRunning || canStart) c.onPrimary else c.textDisabled,
                        fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Transient snackbar for VM messages (route saved, routing failed, no results…).
        toast?.let { msg ->
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
                Text(
                    msg, color = c.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 70.dp).clip(RoundedCornerShape(20.dp)).background(c.primary).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bg: Color,
    tint: Color,
    size: Int = 44,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.5).dp)) }
}

private fun modeLabel(m: TravelMode): String = when (m) {
    TravelMode.CYCLING -> "Bike"
    TravelMode.DRIVING -> "Car"
    TravelMode.WALKING -> "Walk"
    TravelMode.STRAIGHT -> "Direct"
}

/** Travel-mode icons, matching Android (Timeline / DirectionsBike / DirectionsWalk / DirectionsCar). */
private fun modeIcon(m: TravelMode): ImageVector = when (m) {
    TravelMode.STRAIGHT -> Icons.Filled.Timeline
    TravelMode.CYCLING -> Icons.Filled.DirectionsBike
    TravelMode.WALKING -> Icons.Filled.DirectionsWalk
    TravelMode.DRIVING -> Icons.Filled.DirectionsCar
}

private fun Color.toHex6(): String {
    val r = (red * 255).roundToInt().coerceIn(0, 255)
    val g = (green * 255).roundToInt().coerceIn(0, 255)
    val b = (blue * 255).roundToInt().coerceIn(0, 255)
    fun h(v: Int) = v.toString(16).padStart(2, '0')
    return "#${h(r)}${h(g)}${h(b)}"
}
