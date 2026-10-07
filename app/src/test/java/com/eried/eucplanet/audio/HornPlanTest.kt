package com.eried.eucplanet.audio

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.HornSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One horn press, every mode and fallback. A press must never be silent. */
class HornPlanTest {

    private fun plan(mode: String, ready: Boolean = true, headOnly: Boolean = false, external: Boolean = false) =
        HornPlan.decide(mode, ready, headOnly, external)

    @Test fun `wheel mode is exactly today's horn`() {
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false), plan(HornSettings.MODE_WHEEL))
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false), plan(HornSettings.MODE_WHEEL, ready = false))
    }

    @Test fun `custom mode plays the phone sound instead of the wheel`() {
        assertEquals(HornPlan.Plan(wheel = false, phoneSound = true), plan(HornSettings.MODE_SOUND))
    }

    @Test fun `custom mode with no sound falls back to the wheel horn`() {
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false), plan(HornSettings.MODE_SOUND, ready = false))
    }

    @Test fun `headphones only without headphones falls back to the wheel horn`() {
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false),
            plan(HornSettings.MODE_SOUND, headOnly = true, external = false))
        assertEquals(HornPlan.Plan(wheel = false, phoneSound = true),
            plan(HornSettings.MODE_SOUND, headOnly = true, external = true))
    }

    @Test fun `both mode always sends the wheel horn and adds the sound when it can`() {
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = true), plan(HornSettings.MODE_BOTH))
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false), plan(HornSettings.MODE_BOTH, ready = false))
        assertEquals(HornPlan.Plan(wheel = true, phoneSound = false),
            plan(HornSettings.MODE_BOTH, headOnly = true, external = false))
    }

    @Test fun `no combination is silent`() {
        for (mode in HornSettings.MODES + "SOMETHING_NEW") for (ready in listOf(true, false))
            for (h in listOf(true, false)) for (e in listOf(true, false)) {
                val p = plan(mode, ready, h, e)
                assertTrue("$mode ready=$ready headOnly=$h external=$e is silent", p.wheel || p.phoneSound)
            }
    }

    @Test fun `the horn settings survive a backup round trip`() {
        val s = AppSettings(horn = HornSettings(mode = HornSettings.MODE_BOTH, soundName = "goose.ogg", headphonesOnly = true))
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(s.horn, back.horn)
    }

    @Test fun `motor sound settings still read from the old flat backup keys`() {
        val old = JSONObject().put("engineSoundEnabled", true).put("engineType", "V_TWIN")
            .put("engineHeadphonesOnly", true).put("engineVolume", 0.3)
        val s = SettingsJson.fromJson(old)
        assertEquals(true, s.engineSoundEnabled)
        assertEquals("V_TWIN", s.engineType)
        assertEquals(true, s.engineHeadphonesOnly)
        assertEquals(0.3f, s.engineVolume, 0.0001f)
        // and are written back under the same flat keys
        val out = SettingsJson.toJson(s)
        assertEquals("V_TWIN", out.getString("engineType"))
    }
}
