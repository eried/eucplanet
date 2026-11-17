package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.IncomingShareRepository
import com.eried.eucplanet.nav.RouteLinks
import com.eried.eucplanet.nav.SharedRoute
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.share.ShareLink
import com.eried.eucplanet.share.ShareLinks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The links that open the app on the route builder: a live location share
 * (https://eucplanet.ried.no/share#...) and a shared route (.../route#...),
 * as MainActivity.consumeShareIntent takes them from Android's App Links.
 *
 * The iPhone has no universal link for eucplanet.ried.no, so the site's
 * "Open in EUC Planet" hands the same link over under the app's own scheme
 * (Info.plist CFBundleURLTypes): eucplanet://share/<room.key> and
 * eucplanet://route/<1.data>. The https form reads too, for the day a tapped
 * link opens the app by itself.
 */
object IosLinks {
    private const val TAG = "IosLinks"
    private const val SCHEME = "eucplanet://"
    private const val SITE = "https://eucplanet.ried.no/"

    /**
     * A share link that did not read, kept until the screen that shows
     * notices is up: a link can be what launches the app.
     */
    private val _badShare = MutableStateFlow(false)
    val badShare: StateFlow<Boolean> = _badShare.asStateFlow()

    /** Read once, by MainViewController, which tells the rider. */
    fun consumeBadShare(): Boolean {
        val v = _badShare.value
        _badShare.value = false
        return v
    }

    /** What an opened link asks the route builder for. */
    internal sealed interface Opened {
        /** A live location share to join. */
        data class Join(val link: ShareLink) : Opened

        /** A route to open, like a loaded GPX. */
        data class Route(val route: SharedRoute) : Opened

        /** A share link that does not read: the rider is told, as on Android. */
        data object BadShare : Opened

        /** A route link that does not read: the app opens and nothing else, as on Android. */
        data object BadRoute : Opened
    }

    /** What [url] asks for, or null when it is not a share or route link. */
    internal fun read(url: String): Opened? {
        val text = url.trim()
        val web = if (text.startsWith(SCHEME, ignoreCase = true)) SITE + text.substring(SCHEME.length) else text
        return when {
            web.startsWith(ShareLinks.BASE) -> ShareLinks.parse(web)?.let { Opened.Join(it) } ?: Opened.BadShare
            web.startsWith(RouteLinks.BASE) -> RouteLinks.parse(web)?.let { Opened.Route(it) } ?: Opened.BadRoute
            else -> null
        }
    }

    /**
     * An URL the app was opened with (AppDelegate). The link waits where the
     * route builder looks for it, and MainViewController goes there. Returns
     * whether it was ours.
     */
    fun handleUrl(url: String): Boolean {
        when (val opened = read(url) ?: return false) {
            is Opened.Join -> IosAppGraph.pendingShareJoin.offer(opened.link)
            is Opened.Route -> IosAppGraph.incomingShares.offer(IncomingShareRepository.Pending(route = opened.route))
            Opened.BadShare -> _badShare.value = true
            Opened.BadRoute -> Log.w(TAG, "a route link that does not read")
        }
        return true
    }
}
