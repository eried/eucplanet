package com.eried.eucplanet.voice

import com.eried.eucplanet.data.model.VoiceCommandSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sounds a session makes, and the rider's right to stop them.
 *
 * Two riders asked for this from opposite directions: one whose headset plays
 * its own tone when it opens the microphone and was hearing both, and one
 * tired of being told "I did not catch that. Say what can I say for help."
 * twenty times a ride. Both are choices about noise, and the failure mode they
 * share is a value that means neither of the things it could mean: an
 * unrecognised setting falls through every when() to silence, which a rider
 * cannot tell apart from the feature being broken.
 */
class VoiceCueTest {

    @Test fun `the shipped defaults are the chirp and the sentence`() {
        // What a new rider gets: a cue they can hear and an explanation when
        // it goes wrong. Both are the right default and neither is the right
        // permanent answer for everyone, which is why they are settings.
        val s = VoiceCommandSettings()
        assertEquals(VoiceCommandSettings.CUE_BEEP, s.promptCue)
        assertEquals(VoiceCommandSettings.UNKNOWN_MESSAGE, s.unknownCue)
    }

    @Test fun `the headset door ships shut`() {
        // Claiming the system voice-command intent changes a device-wide
        // gesture for every rider on the build, including one who pressed
        // that button meaning to reach their assistant. Opt in, never ship on.
        assertTrue(!VoiceCommandSettings().headsetButton)
    }

    @Test fun `the command language follows the voice until a rider says otherwise`() {
        // Blank is not "no language", it is "the one you are answered in".
        // Three languages that all default to agreeing is what keeps the
        // feature working for the riders who never open this setting.
        assertEquals("", VoiceCommandSettings().recognitionLocale)
    }

    @Test fun `every cue value is one of the offered ones`() {
        // The sets are what SettingsRepository.sanitized() clamps against. A
        // constant that drifted out of its set would pass the compiler and
        // then be thrown away as unrecognised on the first settings read.
        assertTrue(VoiceCommandSettings.CUE_BEEP in VoiceCommandSettings.CUES)
        assertTrue(VoiceCommandSettings.CUE_VOICE in VoiceCommandSettings.CUES)
        assertTrue(VoiceCommandSettings.CUE_NONE in VoiceCommandSettings.CUES)
        assertEquals(3, VoiceCommandSettings.CUES.size)

        assertTrue(VoiceCommandSettings.UNKNOWN_MESSAGE in VoiceCommandSettings.UNKNOWNS)
        assertTrue(VoiceCommandSettings.UNKNOWN_BEEP in VoiceCommandSettings.UNKNOWNS)
        assertTrue(VoiceCommandSettings.UNKNOWN_NONE in VoiceCommandSettings.UNKNOWNS)
        assertEquals(3, VoiceCommandSettings.UNKNOWNS.size)
    }

    @Test fun `the two cue sets are told apart by name, not by value`() {
        // Both sets contain "BEEP" and "NONE". They are separate settings and
        // a shared constant would tie them together the first time one of
        // them gained an option the other should not have.
        assertEquals(VoiceCommandSettings.CUE_BEEP, VoiceCommandSettings.UNKNOWN_BEEP)
        assertTrue(VoiceCommandSettings.CUE_VOICE !in VoiceCommandSettings.UNKNOWNS)
        assertTrue(VoiceCommandSettings.UNKNOWN_MESSAGE !in VoiceCommandSettings.CUES)
    }
}
