package com.eried.eucplanet.service.hud

import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.hud.protocol.HudState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the HUD link both phones read the same way: what a beacon
 * says, which requests are map tiles, and which addresses the subnet sweep
 * knocks on. The sockets under them are each phone's own.
 */
class HudLinkRulesTest {

    // --- the beacon ---

    @Test fun aBeaconGivesTheAddressItNames() {
        val s = HudBeacon.parse("EUCPLANET-HUD v1 ip=10.80.67.125 port=28090", "10.80.67.1", 42L)
        assertEquals(HudBeaconSighting("10.80.67.125", 28090, 42L), s)
    }

    @Test fun aBeaconWithNoAddressFallsBackToWhereItCameFrom() {
        val s = HudBeacon.parse("${HudDiscovery.BEACON_PREFIX} ip=0.0.0.0", "192.168.43.7", 1L)
        assertEquals("192.168.43.7", s?.ip)
        assertEquals(HudDiscovery.DEFAULT_PORT, s?.port)
    }

    @Test fun aBadPortFallsBackToTheDefault() {
        assertEquals(HudDiscovery.DEFAULT_PORT, HudBeacon.parse("${HudDiscovery.BEACON_PREFIX} ip=10.0.0.2 port=99999", "", 1L)?.port)
        assertEquals(HudDiscovery.DEFAULT_PORT, HudBeacon.parse("${HudDiscovery.BEACON_PREFIX} ip=10.0.0.2 port=abc", "", 1L)?.port)
    }

    @Test fun anythingElseOnThePortIsNotABeacon() {
        assertNull(HudBeacon.parse("HELLO ip=10.0.0.2 port=28080", "10.0.0.2", 1L))
        assertNull(HudBeacon.parse("", "10.0.0.2", 1L))
        // Its own address unknown and no source address either: nothing to dial.
        assertNull(HudBeacon.parse("${HudDiscovery.BEACON_PREFIX} ip=0.0.0.0", "", 1L))
    }

    // --- the tile requests ---

    @Test fun aHudIsAnsweredUpToThePhonesOwnMajorVersion() {
        // No version record is a HUD from before there was one: version 1.
        assertTrue(HudMdns.speaks(null))
        assertTrue(HudMdns.speaks("junk"))
        assertTrue(HudMdns.speaks(HudState.PROTOCOL_MAJOR.toString()))
        assertFalse(HudMdns.speaks((HudState.PROTOCOL_MAJOR + 1).toString()))
    }

    @Test fun onlyTileRequestsParse() {
        assertEquals(Triple(15, 18109, 7425), HudTileHttp.parse("GET /tiles/15/18109/7425.png HTTP/1.1"))
        assertNull(HudTileHttp.parse("GET /tiles/15/18109/7425.png/../../x HTTP/1.1"))
        assertNull(HudTileHttp.parse("POST /tiles/1/0/0.png HTTP/1.1"))
        assertNull(HudTileHttp.parse("GET /tiles/1/2/0.png HTTP/1.1"), "x past the edge")
        assertNull(HudTileHttp.parse("GET /health HTTP/1.1"))
    }

    @Test fun aTileAnswersWithItsSizeAndNoTileWithA404() {
        val ok = HudTileHttp.head(1234)
        assertTrue(ok.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(ok.contains("Content-Type: image/png\r\n"))
        assertTrue(ok.contains("Content-Length: 1234\r\n"))
        assertTrue(ok.endsWith("\r\n\r\n"))
        val none = HudTileHttp.head(null)
        assertTrue(none.startsWith("HTTP/1.1 404 Not Found\r\n"))
        assertTrue(none.contains("Content-Length: 0\r\n"))
        assertTrue(none.endsWith("\r\n\r\n"))
    }

    // --- the subnet sweep ---

    /** A network with [cidrs] on which only [open] answers; records every knock. */
    private class FakeNetwork(val cidrs: List<String>, val open: Set<String> = emptySet()) : HudNetwork {
        val knocked = mutableListOf<Pair<String, Int>>()
        private val lock = kotlinx.atomicfu.locks.SynchronizedObject()
        override fun candidateIpv4Cidrs() = cidrs
        override fun tryTcp(host: String, port: Int, timeoutMs: Int): Boolean {
            kotlinx.atomicfu.locks.synchronized(lock) { knocked += host to port }
            return host in open
        }
        override fun watch(events: HudNetworkEvents) {}
        override fun unwatch() {}
        override fun holdRadioAwake(hold: Boolean) {}
    }

    @Test fun theSweepCoversTheWholeSlash24ButNotThePhoneItself() {
        val hosts = HudSubnetProbe(FakeNetwork(emptyList())).expandSubnetIpv4("192.168.43.17/24")!!
        assertEquals(253, hosts.size)
        assertEquals("192.168.43.1", hosts.first())
        assertEquals("192.168.43.254", hosts.last())
        assertTrue("192.168.43.17" !in hosts)
    }

    @Test fun aBigSubnetIsClampedAndASmallOneIsSweptAsItsSlash24() {
        val probe = HudSubnetProbe(FakeNetwork(emptyList()))
        // A /16 would be 65 534 hosts.
        assertEquals(253, probe.expandSubnetIpv4("10.80.67.125/16")!!.size)
        // The iPhone's Personal Hotspot is a /28 on 172.20.10.1.
        val hotspot = probe.expandSubnetIpv4("172.20.10.1/28")!!
        assertTrue("172.20.10.2" in hotspot && "172.20.10.14" in hotspot)
        assertTrue("172.20.10.1" !in hotspot)
        assertEquals(emptyList(), probe.expandSubnetIpv4("10.0.0.1/32"))
        assertNull(probe.expandSubnetIpv4("not an address"))
        assertNull(probe.expandSubnetIpv4("10.0.0.300/24"))
    }

    @Test fun theProbeFindsTheHudOnWhicheverSubnetItIsOn() = runBlocking {
        val net = FakeNetwork(listOf("192.168.1.20/24", "172.20.10.1/28"), open = setOf("172.20.10.2"))
        val found = HudSubnetProbe(net).probe(28080)
        assertEquals("172.20.10.2", found)
        assertTrue(net.knocked.all { it.second == 28080 })
        assertTrue(net.knocked.none { it.first == "192.168.1.20" || it.first == "172.20.10.1" })
    }

    @Test fun noLocalNetworkMeansNoKnocking() = runBlocking {
        val net = FakeNetwork(emptyList())
        assertNull(HudSubnetProbe(net).probe(28080))
        assertTrue(net.knocked.isEmpty())
    }
}
