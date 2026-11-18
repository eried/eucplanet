package com.eried.eucplanet.ui.navigator

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The friend markers are drawn by the page from the JSON sharePeersJson
 * pushes. Nothing ties the two sides together at compile time, so a field the
 * page reads and Kotlin does not send is simply undefined: the page keeps
 * running and the feature behind that field quietly never happens.
 */
class PeerMarkerContractTest {

    private val page = File("src/main/java/com/eried/eucplanet/ui/navigator/MapHtml.kt").readText()
    private val screen = File("src/main/java/com/eried/eucplanet/ui/navigator/RouteBuilderScreen.kt").readText()

    /** The page's peer code: label, dot, tap handling and nativeSetPeers. */
    private val peerScript: String
        get() {
            val from = page.indexOf("function peerLabelHtml")
            val to = page.indexOf("window.nativeFitPoints")
            assertTrue("peer script not found", from in 1 until to)
            return page.substring(from, to)
        }

    @Test fun `every peer field the page reads is one kotlin sends`() {
        val body = screen.substringAfter("private fun sharePeersJson(")
            .substringBefore("private fun jsString(")
        val sent = Regex("""put\(\s*"(\w+)"""").findAll(body).map { it.groupValues[1] }.toSet()
        assertTrue("sharePeersJson not found", "statsText" in sent && "id" in sent)
        val read = Regex("""\bp\.(\w+)""").findAll(peerScript).map { it.groupValues[1] }.toSet()
        assertTrue("the page reads no peer fields", read.isNotEmpty())
        val missing = read - sent
        assertTrue("the page reads peer fields kotlin never sends: $missing", missing.isEmpty())
    }

    @Test fun `a friend who shares stats has a label to open`() {
        // The tap handler and the group list's fly-to both ask peerHasMore
        // before opening a label, so it has to see the stats the label draws.
        val hasMore = peerScript.substringAfter("function peerHasMore(")
            .substringBefore("function peerSetExpanded(")
        assertTrue("peerHasMore does not look at the stats text", hasMore.contains("p.statsText"))
    }
}
