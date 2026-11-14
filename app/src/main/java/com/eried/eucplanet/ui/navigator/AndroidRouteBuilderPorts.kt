package com.eried.eucplanet.ui.navigator

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.nav.offline.OfflineTiles
import com.eried.eucplanet.ui.dashboard.toPhoneLocation
import com.eried.eucplanet.util.GpxData
import com.eried.eucplanet.util.GpxIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The route builder on Android: TripRepository's fixes, BatteryManager, the
 * offline tile store, the document provider with GpxIO's XmlPullParser
 * reader, and HttpURLConnection for a shortened map link.
 */
class AndroidRouteBuilderPorts(
    private val context: Context,
    private val trips: TripRepository,
    private val offlineTiles: OfflineTiles,
) : RouteBuilderPorts {
    // App-lifetime, like the repository whose fixes it mirrors.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val currentLocation: StateFlow<PhoneLocation?> = trips.currentLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.currentLocation.value?.toPhoneLocation())

    override val lastKnownLocation: StateFlow<PhoneLocation?> = trips.lastKnownLocation.map { it?.toPhoneLocation() }
        .stateIn(scope, SharingStarted.Eagerly, trips.lastKnownLocation.value?.toPhoneLocation())

    override fun startLocationUpdates() = trips.startLocationUpdates()

    override fun phoneBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    override val downloadingTiles: Flow<Set<String>> = offlineTiles.progress.map { it.keys }

    override fun hasTile(name: String): Boolean = offlineTiles.store.has(name)

    override fun servedTiles(): Set<String>? = offlineTiles.served()

    override fun readGpx(file: String): GpxData? =
        context.contentResolver.openInputStream(Uri.parse(file))?.use { GpxIO.parse(it) }

    override fun writeGpx(file: String, route: NavRoute) {
        // Keep the .gpx extension even if the rider cleared it in the
        // system save dialog; otherwise the file is hard to spot and
        // won't filter back into the open dialog later.
        val target = ensureGpxExtension(Uri.parse(file))
        context.contentResolver.openOutputStream(target)?.use { GpxIO.write(route, it) }
    }

    /** Renames the just-created document to append ".gpx" when it is missing. */
    private fun ensureGpxExtension(uri: Uri): Uri {
        val name = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return uri
        if (name.endsWith(".gpx", ignoreCase = true)) return uri
        return runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, "$name.gpx")
        }.getOrNull() ?: uri
    }

    /**
     * `maps.app.goo.gl` typically chains 2-3 redirects: shortlink ->
     * `www.google.com/maps?...` -> `consent.google.com/?continue=...` ->
     * actual maps page with `@lat,lng`. We follow them all manually
     * (instanceFollowRedirects is too eager for cross-host hops on
     * Android).
     */
    override fun resolveShortLink(url: String): String? {
        return try {
            var current = url
            repeat(5) {
                val conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 4000
                    readTimeout = 4000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) EucPlanet")
                }
                try {
                    val code = conn.responseCode
                    if (code !in 300..399) {
                        // Reached a 2xx (or error). Return the URL we tried.
                        return current
                    }
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return current
                    current = if (loc.startsWith("http", ignoreCase = true)) loc
                              else java.net.URL(java.net.URL(current), loc).toString()
                } finally {
                    conn.disconnect()
                }
            }
            current
        } catch (e: Exception) {
            android.util.Log.w("EucShare", "shortlink resolve failed: $e")
            null
        }
    }
}
