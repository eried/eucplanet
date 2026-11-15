package btools.router

import kotlin.test.Test
import kotlin.test.assertEquals

/** The app reads turn hints through these getters, from another module. */
class VoiceHintAccessTest {

    @Test
    fun gettersReadTheHint() {
        val h = VoiceHint()
        h.cmd = VoiceHint.TL
        h.roundaboutExit = 3
        h.indexInTrack = 7
        h.distanceToNext = 12.5
        h.angle = -90f
        assertEquals(VoiceHint.TL, h.getCommand())
        assertEquals(3, h.getExitNumber())
        assertEquals(7, h.getIndexInTrack())
        assertEquals(12.5, h.getDistanceToNext())
        assertEquals(-90f, h.getAngle())
        val l = VoiceHintList()
        l.list.add(h)
        assertEquals(1, l.getList().size)
    }
}
