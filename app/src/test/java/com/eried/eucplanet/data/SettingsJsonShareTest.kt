package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ShareSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonShareTest {
    @Test fun shareSettingsRoundTrip() {
        val s = AppSettings().copy(share = ShareSettings(trailMinutes = 12, shareStatsDefault = false,
            lastIdentityMode = "PROFILE", lastSessionName = "Erwin", relayUrl = "wss://x.example"))
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(s.share, back.share)
    }
    /** The ride the rider left is offered back to them as "Rejoin last ride",
     *  so the link has to survive a restart, not just the process. */
    @Test fun lastLinkUrlRoundTrip() {
        val link = "https://eucplanet.ried.no/share#AAAAAAAAAAAAAAAAAAAAAA.BBBBBBBBBBBBBBBBBBBBBB"
        val s = AppSettings().copy(share = ShareSettings(lastLinkUrl = link))
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(link, back.share.lastLinkUrl)
        assertEquals(s.share, back.share)
    }
    /** Nothing to rejoin is the default, and an older settings file that
     *  predates the field must land on it rather than on a null string. */
    @Test fun lastLinkUrlDefaultsEmpty() {
        assertEquals("", ShareSettings().lastLinkUrl)
        val legacy = JSONObject("""{"share":{"trailMinutes":7}}""")
        assertEquals("", SettingsJson.fromJson(legacy).share.lastLinkUrl)
    }
    @Test fun shareSettingsDefaultsOnEmptyJson() {
        assertEquals(ShareSettings(), SettingsJson.fromJson(JSONObject("{}")).share)
    }
    /** The relay is dialled as a WebSocket, so SettingsRepository.sanitized()
     *  resets anything that is not a ws / wss URL to the default. This pins
     *  the rule it applies. */
    @Test fun onlyWebSocketRelayUrlsAreValid() {
        assertTrue(ShareSettings.isValidRelayUrl("wss://eucshare.ried.no"))
        assertTrue(ShareSettings.isValidRelayUrl("ws://192.168.1.10:8080"))
        assertFalse(ShareSettings.isValidRelayUrl("https://eucshare.ried.no"))
        assertFalse(ShareSettings.isValidRelayUrl("eucshare.ried.no"))
        assertFalse(ShareSettings.isValidRelayUrl(""))
        assertTrue(ShareSettings.isValidRelayUrl(ShareSettings.DEFAULT_RELAY_URL))
        assertEquals(ShareSettings.DEFAULT_RELAY_URL, ShareSettings().relayUrl)
    }
}
