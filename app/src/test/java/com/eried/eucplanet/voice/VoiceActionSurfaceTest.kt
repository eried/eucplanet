package com.eried.eucplanet.voice

import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.data.model.ActionSurface
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Listening reaches every surface a rider can press without looking.
 *
 * The point of putting it in the catalog rather than wiring it per surface:
 * a Flic, the volume keys, the watch stem, the HUD and a dashboard tile all ask
 * the catalog what they may bind, so one entry covers all of them. This asserts
 * that rather than trusting it, because the failure is silent: the action would
 * simply not appear in a picker, and nothing anywhere would say why.
 */
class VoiceActionSurfaceTest {

    private val listen = "VOICE_LISTEN"

    @Test
    fun `the catalog knows it`() {
        assertTrue(ActionCatalog.all.any { it.key == listen })
    }

    @Test
    fun `every eyes-free surface can bind it`() {
        // The watch is the one worth naming: a rider with a bound stem button
        // asks their wheel a question without taking a hand off anything.
        for (surface in listOf(
            ActionSurface.FLIC,
            ActionSurface.VOLUME_KEY,
            ActionSurface.WATCH,
            ActionSurface.TRIGGER,
            ActionSurface.DASHBOARD,
        )) {
            assertTrue("$surface cannot bind $listen", listen in ActionCatalog.keysFor(surface))
        }
    }

    @Test
    fun `it is eyes-free, which is what puts it on those surfaces`() {
        val spec = ActionCatalog.byKey(listen)
        assertTrue("the action is missing from the catalog", spec != null)
        assertTrue("not eyes-free, so no physical surface would offer it", spec!!.isEyesFreeSafe)
    }

    @Test
    fun `the voice report it pairs with is still there`() {
        // The two share a dashboard slot and swap between them. Renaming either
        // key without the other breaks the swap, which is a settings string
        // edit away and would fail silently.
        assertTrue(ActionCatalog.all.any { it.key == "VOICE_ANNOUNCE" })
    }
}
