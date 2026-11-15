package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.NavRoute
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.ui.navigator.RouteBuilderPorts
import com.eried.eucplanet.util.GpxData
import com.eried.eucplanet.util.GpxText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.NSObject
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * The route builder on the iPhone: CoreLocation's fixes ([IosLocation]), the
 * UIDevice battery, the downloaded routing squares (IosAppGraph.offlineTiles), the Files
 * picker's GPX through the shared [GpxText] reader, and an NSURLSession that
 * walks a short link's redirects. Android's is AndroidRouteBuilderPorts.
 */
internal class IosRouteBuilderPorts : RouteBuilderPorts {
    override val currentLocation: StateFlow<PhoneLocation?> = IosLocation.fix

    override val lastKnownLocation: StateFlow<PhoneLocation?> = IosLocation.lastKnown

    override fun startLocationUpdates() = IosLocation.start()

    override fun phoneBatteryPercent(): Int = IosPhoneDevice.batteryPercent()

    // The offline areas, as Android's: the queue, the store and the server's list.
    override val downloadingTiles: Flow<Set<String>> = IosAppGraph.offlineTiles.progress.map { it.keys }

    override fun hasTile(name: String): Boolean = IosAppGraph.offlineStore.has(name)

    override fun servedTiles(): Set<String>? = IosAppGraph.offlineTiles.served()

    /** [file] is a file URL the Files picker handed over (a copy in the app's tmp). */
    override fun readGpx(file: String): GpxData? {
        val url = fileUrl(file) ?: return null
        val access = url.startAccessingSecurityScopedResource()
        try {
            val bytes = NSData.dataWithContentsOfURL(url)?.toByteArray() ?: return null
            return GpxText.parse(bytes)
        } finally {
            if (access) url.stopAccessingSecurityScopedResource()
        }
    }

    /**
     * [file] is where the save picker will put the route ([IosGpxFiles]):
     * written there first, then the picker moves it to the folder the rider
     * picks. The name already ends in .gpx, as Android makes sure of.
     */
    override fun writeGpx(file: String, route: NavRoute) {
        val url = fileUrl(file) ?: error("no file $file")
        check(GpxText.write(route).encodeToByteArray().toNSData().writeToURL(url, atomically = true)) {
            "GPX not written"
        }
        IosGpxFiles.written(file)
    }

    private fun fileUrl(file: String): NSURL? =
        if (file.startsWith("file:")) NSURL.URLWithString(file) else NSURL.fileURLWithPath(file)

    /**
     * Follows a shortened map link's redirects by hand, up to five hops, as
     * Android's HttpURLConnection walk does (`maps.app.goo.gl` chains two or
     * three, through consent.google.com), and returns where it lands.
     */
    override fun resolveShortLink(url: String): String? = try {
        var current = url
        for (hop in 0 until 5) {
            val next = redirectOf(current) ?: return current
            current = next
        }
        current
    } catch (e: Exception) {
        Log.w("EucShare", "shortlink resolve failed: $e")
        null
    }

    /** The Location of [url]'s redirect, null when it answers anything else. Throws on no answer. */
    private fun redirectOf(url: String): String? {
        val target = NSURL.URLWithString(url) ?: throw IllegalArgumentException("bad url")
        val req = NSMutableURLRequest.requestWithURL(target, NSURLRequestReloadIgnoringLocalCacheData, 4.0)
        req.setHTTPMethod("GET")
        req.setValue("Mozilla/5.0 (iPhone) EucPlanet", forHTTPHeaderField = "User-Agent")
        val done = dispatch_semaphore_create(0)
        var code = -1
        var location: String? = null
        var failure: String? = null
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.ephemeralSessionConfiguration, noRedirects, null,
        )
        session.dataTaskWithRequest(req) { _, response, error ->
            val http = response as? NSHTTPURLResponse
            if (http == null) failure = error?.localizedDescription ?: "no response"
            else {
                code = http.statusCode.toInt()
                location = http.allHeaderFields.entries
                    .firstOrNull { (k, _) -> k.toString().equals("Location", ignoreCase = true) }?.value?.toString()
            }
            dispatch_semaphore_signal(done)
        }.resume()
        dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 8_000L * NSEC_PER_MSEC.toLong()))
        session.finishTasksAndInvalidate()
        if (code < 0) throw IllegalStateException(failure ?: "timed out")
        if (code !in 300..399) return null
        val loc = location?.takeIf { it.isNotBlank() } ?: return null
        return if (loc.startsWith("http", ignoreCase = true)) loc
        else NSURL.URLWithString(loc, relativeToURL = target)?.absoluteString ?: loc
    }

    // An Objective-C subclass cannot be a Kotlin object: one instance, kept here.
    private val noRedirects = NoRedirects()

    /** Hands every redirect back unfollowed, so the walk sees each hop. */
    private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) = completionHandler(null)
    }
}
