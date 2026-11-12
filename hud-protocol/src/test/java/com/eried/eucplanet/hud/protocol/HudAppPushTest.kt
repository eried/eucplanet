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

    @Test fun `an older HUD is offered the app, the same or a newer one is not`() {
        assertTrue(HudAppPush.isOlder(hudVersionCode = 300015, carriedVersionCode = 300016))
        assertFalse(HudAppPush.isOlder(hudVersionCode = 300016, carriedVersionCode = 300016))
        assertFalse(HudAppPush.isOlder(hudVersionCode = 300017, carriedVersionCode = 300016))
    }

    @Test fun `an unknown version on either side offers nothing`() {
        assertFalse(HudAppPush.isOlder(hudVersionCode = 0, carriedVersionCode = 300016))
        assertFalse(HudAppPush.isOlder(hudVersionCode = 300015, carriedVersionCode = 0))
    }

    @Test fun `a pair greeting without a version code still reads`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<HudCommand>(
            """{"type":"com.eried.eucplanet.hud.protocol.HudCommand.Pair","hudId":"x","hudVersion":"0.1.14"}"""
        ) as HudCommand.Pair
        assertEquals(0, old.hudVersionCode)
    }
}
