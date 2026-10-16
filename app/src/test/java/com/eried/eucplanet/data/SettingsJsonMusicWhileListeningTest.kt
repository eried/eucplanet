package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.VoiceCommandSettings
import com.eried.eucplanet.data.repository.sanitized
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Music while listening" survives a save and load, defaults to pausing the
 * music, and an unknown value from a newer backup falls back to pausing
 * rather than to a mode this build cannot apply.
 */
class SettingsJsonMusicWhileListeningTest {

    @Test fun everyMode_roundTripsThroughJson() {
        for (mode in VoiceCommandSettings.MUSIC_MODES) {
            val s = AppSettings(voiceCommands = VoiceCommandSettings(musicWhileListening = mode))
            val restored = SettingsJson.fromJson(SettingsJson.toJson(s), AppSettings())
            assertEquals(mode, restored.voiceCommands.musicWhileListening)
        }
    }

    @Test fun missingKey_pausesTheMusic() {
        assertEquals(
            VoiceCommandSettings.MUSIC_PAUSE,
            SettingsJson.fromJson(JSONObject(), AppSettings()).voiceCommands.musicWhileListening,
        )
    }

    @Test fun unknownMode_isSanitizedToPause() {
        val s = AppSettings(voiceCommands = VoiceCommandSettings(musicWhileListening = "LOUDER"))
        assertEquals(VoiceCommandSettings.MUSIC_PAUSE, s.sanitized().voiceCommands.musicWhileListening)
    }
}
