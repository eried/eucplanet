package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Four hidden recordings left the app together with their sound banks. A rider
 * who had picked one before it was hidden still has its key in their settings.
 */
class EngineRemovedKeysTest {

    @Test
    fun `a removed engine resolves to a listed engine of the same kind`() {
        assertEquals("TRACTOR", EngineProfile.byKey("SAMPLED_TRACTOR").key)
        assertEquals("QUAD_BIKE", EngineProfile.byKey("SAMPLED_QUAD_ATV").key)
        assertEquals("FOUR_STROKE_SINGLE", EngineProfile.byKey("SAMPLED_LAWNMOWER").key)
        assertEquals("SAMPLED_DIESEL_IVECO", EngineProfile.byKey("SAMPLED_BIG_DIESEL").key)
    }

    @Test
    fun `every stand-in exists and is offered in the picker`() {
        for ((gone, standIn) in EngineProfile.REMOVED) {
            assertTrue("$gone is still a profile", EngineProfile.PROFILES.none { it.key == gone })
            val p = EngineProfile.PROFILES.firstOrNull { it.key == standIn }
            assertTrue("$standIn does not exist", p != null)
            assertFalse("$standIn is hidden", p!!.preview)
        }
    }
}
