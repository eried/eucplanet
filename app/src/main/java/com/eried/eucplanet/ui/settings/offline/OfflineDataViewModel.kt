package com.eried.eucplanet.ui.settings.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.GeoPoint
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.nav.CurrentRouteStore
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.nav.offline.RemoteTile
import com.eried.eucplanet.nav.offline.RoutingTiles
import com.eried.eucplanet.nav.offline.TileIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The offline routing areas screen: what is on the phone, on the server, and downloading. */
class OfflineDataViewModel(
    private val context: Context,
    private val tiles: OfflineTiles,
    private val settings: SettingsRepository,
    private val currentRoute: CurrentRouteStore,
    private val trips: com.eried.eucplanet.data.repository.TripRepository,
) : ViewModel() {

    private val remote = MutableStateFlow<List<RemoteTile>?>(null)
    private val loaded = MutableStateFlow(false)
    /** Bumped after a delete or a finished download so the disk is read again. */
    private val diskTick = MutableStateFlow(0)

    val wifiAutoUpdate: StateFlow<Boolean> = settings.settings
        .map { it.offlineRouting.wifiAutoUpdate }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<OfflineDataState> =
        combine(remote, loaded, tiles.progress, diskTick) { r, done, progress, _ ->
            val downloaded = tiles.store.downloaded()
            val outdated = r?.let { tiles.outdated(it).toSet() }.orEmpty()
            OfflineDataState(
                tiles = OfflineDataState.tiles(downloaded, r, progress, outdated),
                usedBytes = downloaded.values.sum(),
                freeBytes = runCatching { StatFs(tiles.store.dir.path).availableBytes }.getOrDefault(0L),
                listError = done && r == null,
                loading = !done,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineDataState())

    init {
        refresh()
        // A download that finishes leaves the progress list: read the disk again.
        viewModelScope.launch { tiles.progress.collect { diskTick.value++ } }
    }

    fun refresh() {
        viewModelScope.launch {
            loaded.value = false
            remote.value = tiles.remote()
            loaded.value = true
        }
    }

    fun download(names: Collection<String>) {
        viewModelScope.launch { tiles.download(names) }
    }

    fun cancel(name: String) {
        tiles.cancel(name)
        diskTick.value++
    }

    fun delete(name: String) {
        tiles.delete(name)
        diskTick.value++
    }

    fun updateAll() = download(state.value.tiles.values.filter { it.status == TileStatus.UPDATE }.map { it.name })

    fun setWifiAutoUpdate(on: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(offlineRouting = it.offlineRouting.copy(wifiAutoUpdate = on)) }
            tiles.scheduleAutoUpdate(on)
        }
    }

    /** Squares the planned route crosses that are not on the phone yet, or
     *  null when no route is planned. */
    fun routeTilesMissing(): List<String>? {
        val route = currentRoute.get() ?: return null
        val pts = route.geometry.ifEmpty { route.waypoints.map { GeoPoint(it.lat, it.lng) } }
        if (pts.size < 2) return null
        return missing(pts.zipWithNext().flatMap { (a, b) -> com.eried.eucplanet.nav.offline.RouterPlan.legTiles(a, b) }.toSet())
    }

    /** The squares within about [km] of the rider not downloaded yet, or
     *  null when where the rider is is not known. */
    fun nearTilesMissing(km: Double): List<String>? {
        val here = trips.lastKnownLocation.value ?: return null
        return missing(RoutingTiles.tilesAround(GeoPoint(here.latitude, here.longitude), km))
    }

    /** Which of [names] still need a download, now the server list is known. */
    fun missingOf(names: List<String>): List<String> = missing(names.toSet())

    private fun missing(names: Set<String>): List<String> {
        // Squares the server lists; open sea has none. Without the list,
        // all of them, and a missing one fails without retrying. A square
        // with routing but an old or missing map (one downloaded before its
        // map existed) still counts: the download fetches what it lacks.
        val r = remote.value
        val byName = r?.associateBy { it.name }
        return names.filter { name ->
            if (byName == null) return@filter !tiles.store.has(name)
            val t = byName[name] ?: return@filter false
            TileIndex.fetchBytes(tiles.store.finalFile(name), tiles.store.mapFile(name), t) > 0
        }
    }

    /** What downloading [names] fetches, routing and maps it does not have. */
    fun fetchBytes(names: List<String>): Long {
        val byName = remote.value?.associateBy { it.name }
            ?: return names.sumOf { state.value.tiles[it]?.bytes ?: 0L }
        return names.sumOf { n ->
            byName[n]?.let { TileIndex.fetchBytes(tiles.store.finalFile(n), tiles.store.mapFile(n), it) } ?: 0L
        }
    }

    /** True when a route is planned, so its squares can be fetched. */
    fun hasRoute(): Boolean = currentRoute.get()?.let { it.waypoints.size >= 2 || it.geometry.size >= 2 } == true

    /** Where the map opens: the planned route's start, else the rider, else
     *  null (the whole of Europe). */
    fun startPoint(): GeoPoint? =
        currentRoute.get()?.waypoints?.firstOrNull()?.let { GeoPoint(it.lat, it.lng) }
            ?: trips.lastKnownLocation.value?.let { GeoPoint(it.latitude, it.longitude) }

    fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun mapType(): String = settings.currentOrLoad().navMapType
        .ifBlank { com.eried.eucplanet.hud.protocol.MapLayers.OSM }
}
