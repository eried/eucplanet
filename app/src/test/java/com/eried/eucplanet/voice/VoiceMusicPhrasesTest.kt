package com.eried.eucplanet.voice

import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.data.model.ActionSurface
import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rider's music by voice, and by every button that can press the same keys.
 *
 * "Music" on its own starts it, and every longer phrase that contains the word
 * has to land somewhere else. That works because the matcher prefers the
 * longest name that fits, so it needs holding still, as with the light.
 */
class VoiceMusicPhrasesTest {

    private val music = listOf("MUSIC_START", "MEDIA_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS")

    // The English phrasings, as shipped, beside the neighbours they could
    // collide with.
    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf("SPEED" to "Speed"),
        reportNames = emptyMap(),
        splitName = "last split",
        helpPhrases = "help,what can I say",
        specialPhrases = mapOf(VoiceVocabulary.Special.LAST_TRIP to "last trip,previous ride"),
        actionPhrases = mapOf(
            "MUSIC_START" to "play music,start music,resume music,music",
            "MEDIA_PAUSE" to "pause music,stop music",
            "MEDIA_NEXT" to "next song,next track,skip song",
            "MEDIA_PREVIOUS" to "previous song,previous track,last song",
            "RECORD_START" to "start recording,record",
            "RECORD_STOP" to "stop recording",
        ),
    )

    private fun key(said: String): String? =
        (VoiceCommandMatcher.match(said, vocabulary, emptySet()) as? VoiceMatch.Hit)?.term?.key

    @Test fun `each phrase lands on its own key`() {
        mapOf(
            "music" to "MUSIC_START",
            "play music" to "MUSIC_START",
            "start music" to "MUSIC_START",
            "pause music" to "MEDIA_PAUSE",
            "stop music" to "MEDIA_PAUSE",
            "next song" to "MEDIA_NEXT",
            "skip song" to "MEDIA_NEXT",
            "previous song" to "MEDIA_PREVIOUS",
            "last song" to "MEDIA_PREVIOUS",
            "stop recording" to "RECORD_STOP",
            "last trip" to VoiceVocabulary.Special.LAST_TRIP,
        ).forEach { (said, expected) -> assertEquals("\"$said\"", expected, key(said)) }
    }

    @Test fun `the spoken keys are the catalog's own`() {
        // Voice runs them through the same dispatcher as a Flic, so a key the
        // catalog does not know would be heard, acknowledged and then dropped.
        for (k in music) {
            assertTrue("$k is not a spoken action", VoicePhrases.ACTIONS.any { it.first == k })
            assertNotNull("$k is not in the catalog", ActionCatalog.byKey(k))
        }
    }

    @Test fun `every eyes-free surface can bind them`() {
        for (k in music) for (surface in listOf(
            ActionSurface.FLIC,
            ActionSurface.VOLUME_KEY,
            ActionSurface.WATCH,
            ActionSurface.TRIGGER,
            ActionSurface.DASHBOARD,
        )) {
            assertTrue("$surface cannot bind $k", k in ActionCatalog.keysFor(surface))
        }
    }
}
