package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonCustomEngineTest {
    @Test fun `custom engine fields survive a json round-trip`() {
        val s = AppSettings(
            engineType = "CUSTOM",
            engineCustomModulatePitch = false,
            engineCustomSounds = """{"idle_loop":"content://a"}""",
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(s), AppSettings())
        assertEquals("CUSTOM", restored.engineType)
        assertEquals(false, restored.engineCustomModulatePitch)
        assertEquals("""{"idle_loop":"content://a"}""", restored.engineCustomSounds)
    }

    @Test fun `defaults apply when fields absent`() {
        val restored = SettingsJson.fromJson(JSONObject("{}"), AppSettings())
        assertEquals(true, restored.engineCustomModulatePitch)
        assertEquals("", restored.engineCustomSounds)
    }
}
