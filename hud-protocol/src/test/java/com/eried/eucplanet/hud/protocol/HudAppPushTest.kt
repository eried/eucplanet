package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone offers to send the HUD its app, and to which HUDs. */
class HudAppPushTest {

    @Test fun `only a HUD that knows the posts is offered them`() {
        assertTrue(HudAppPush.canReceive(HudState.PROTOCOL_MAJOR, HudAppPush.MIN_MINOR))
        assertTrue(HudAppPush.canReceive(HudState.PROTOCOL_MAJOR, HudAppPush.MIN_MINOR + 5))
        assertFalse(HudAppPush.canReceive(HudState.PROTOCOL_MAJOR, HudAppPush.MIN_MINOR - 1))
        // A HUD from before the version fields, and one from another major.
        assertFalse(HudAppPush.canReceive(0, 0))
        assertFalse(HudAppPush.canReceive(HudState.PROTOCOL_MAJOR + 1, HudAppPush.MIN_MINOR))
    }

    @Test fun `this build of the HUD is one that takes them`() {
        assertTrue(HudAppPush.canReceive(HudState.PROTOCOL_MAJOR, HudState.PROTOCOL_MINOR))
    }

    @Test fun `an older HUD is offered the app, a newer one is not`() {
        assertEquals(HudAppPush.Offer.SEND, HudAppPush.offer(300015, "aaa", 300016, "bbb"))
        assertEquals(HudAppPush.Offer.NONE, HudAppPush.offer(300017, "aaa", 300016, "bbb"))
    }

    @Test fun `another build of the same version is offered like an older one`() {
        assertEquals(HudAppPush.Offer.SEND, HudAppPush.offer(300016, "aaa", 300016, "bbb"))
    }

    @Test fun `the very same build can still go, after a question`() {
        assertEquals(HudAppPush.Offer.SAME_BUILD, HudAppPush.offer(300016, "bbb", 300016, "bbb"))
    }

    @Test fun `an unknown build reads as another build`() {
        assertEquals(HudAppPush.Offer.SEND, HudAppPush.offer(300016, "", 300016, "bbb"))
        assertEquals(HudAppPush.Offer.SEND, HudAppPush.offer(300016, "aaa", 300016, ""))
        assertEquals(HudAppPush.Offer.SEND, HudAppPush.offer(300016, "", 300016, ""))
    }

    @Test fun `an unknown version on either side offers nothing`() {
        assertEquals(HudAppPush.Offer.NONE, HudAppPush.offer(0, "aaa", 300016, "bbb"))
        assertEquals(HudAppPush.Offer.NONE, HudAppPush.offer(300015, "aaa", 0, "bbb"))
    }

    @Test fun `the HUD keeps the same version or a newer one, never an older`() {
        assertTrue(HudAppPush.accepts(runningVersionCode = 300016, postedVersionCode = 300016))
        assertTrue(HudAppPush.accepts(runningVersionCode = 300016, postedVersionCode = 300017))
        assertFalse(HudAppPush.accepts(runningVersionCode = 300016, postedVersionCode = 300015))
    }

    @Test fun `a pair greeting without a version code still reads`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<HudCommand>(
            """{"type":"com.eried.eucplanet.hud.protocol.HudCommand.Pair","hudId":"x","hudVersion":"0.1.14"}"""
        ) as HudCommand.Pair
        assertEquals(0, old.hudVersionCode)
        assertEquals("", old.hudBuild)
    }
}
