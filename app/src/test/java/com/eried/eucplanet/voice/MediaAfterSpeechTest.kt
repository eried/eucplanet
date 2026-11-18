package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Play, next and previous wait until the reply is over and focus is back.
 * Pause goes at once, so handing focus back does not restart the song.
 */
class MediaAfterSpeechTest {

    @Test fun `play next and previous wait for the reply`() {
        assertEquals(
            setOf("MUSIC_START", "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS"),
            MEDIA_AFTER_SPEECH,
        )
    }

    @Test fun `pause is sent at once`() {
        assertFalse("MEDIA_PAUSE" in MEDIA_AFTER_SPEECH)
    }
}
