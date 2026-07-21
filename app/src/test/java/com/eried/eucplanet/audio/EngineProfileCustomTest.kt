package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileCustomTest {
    @Test fun `raw section is not custom, uri section is`() {
        assertFalse(SampleSection("engine_v8_cobra", 0, 100).isCustom)
        assertTrue(SampleSection("", 0, 0, uri = "content://x/1").isCustom)
    }

    @Test fun `profiles default to non-pitch-modulated`() {
        assertFalse(EngineProfile.byKey("V8_MUSCLE").pitchModulated)
    }

    @Test fun `custom key constant is stable`() {
        assertEquals("CUSTOM", EngineProfile.CUSTOM_KEY)
    }
}
