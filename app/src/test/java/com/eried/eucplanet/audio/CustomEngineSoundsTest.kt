package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEngineSoundsTest {

    @Test fun `slots round-trip through json`() {
        val slots = mapOf(CustomSlot.IDLE to "content://a", CustomSlot.REV to "content://b")
        val json = CustomEngineSounds.encodeSlots(slots)
        assertEquals(slots, CustomEngineSounds.parseSlots(json))
    }

    @Test fun `parse tolerates blank and malformed json`() {
        assertTrue(CustomEngineSounds.parseSlots("").isEmpty())
        assertTrue(CustomEngineSounds.parseSlots("not json").isEmpty())
    }

    @Test fun `status is required when main missing, empty-optional for others`() {
        val canOpen = { _: String -> true }
        assertEquals(SlotStatus.REQUIRED, CustomEngineSounds.statusFor(CustomSlot.IDLE, null, canOpen))
        assertEquals(SlotStatus.EMPTY_OPTIONAL, CustomEngineSounds.statusFor(CustomSlot.REV, "", canOpen))
    }

    @Test fun `status reflects whether the uri opens`() {
        assertEquals(SlotStatus.OK, CustomEngineSounds.statusFor(CustomSlot.IDLE, "content://a") { true })
        assertEquals(SlotStatus.MISSING, CustomEngineSounds.statusFor(CustomSlot.IDLE, "content://a") { false })
    }

    @Test fun `single-file mode builds an idle-only pitched profile`() {
        val slots = mapOf(CustomSlot.IDLE to "content://a", CustomSlot.REV to "content://b")
        val p = CustomEngineSounds.buildProfile(slots, modulatePitch = true)
        assertEquals(EngineProfile.CUSTOM_KEY, p.key)
        assertTrue(p.pitchModulated)
        assertEquals(setOf(CustomSlot.IDLE), p.sampleSections!!.keys)
        assertEquals("content://a", p.sampleSections!![CustomSlot.IDLE]!!.uri)
    }

    @Test fun `multi mode keeps every provided slot and is not pitched`() {
        val slots = mapOf(
            CustomSlot.IDLE to "content://a",
            CustomSlot.REV to "content://b",
            CustomSlot.SHUTDOWN to "content://c",
        )
        val p = CustomEngineSounds.buildProfile(slots, modulatePitch = false)
        assertTrue(!p.pitchModulated)
        assertEquals(setOf(CustomSlot.IDLE, CustomSlot.REV, CustomSlot.SHUTDOWN), p.sampleSections!!.keys)
    }

    @Test fun `no main file yields an empty (silent) section map`() {
        val p = CustomEngineSounds.buildProfile(emptyMap(), modulatePitch = true)
        assertTrue(p.sampleSections!!.isEmpty())
    }
}
