package com.eried.eucplanet.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The voice setting stores "en_US"; a recogniser wants "en-US".
 *
 * Found in a log line, not in a crash, which is the point: passing the stored
 * form through does not throw. The recogniser shrugs and uses the device
 * language instead, so a Spanish rider asking for "consumo" is listened to in
 * English and simply never understood.
 */
class VoiceLocaleTagTest {

    @Test
    fun `the stored underscore form becomes a language tag`() {
        assertEquals("en-US", VoiceLocaleTag.tag("en_US"))
        assertEquals("es-419", VoiceLocaleTag.tag("es_419"))
        assertEquals("pt-BR", VoiceLocaleTag.tag("pt_BR"))
    }

    @Test
    fun `a tag that is already a tag is left alone`() {
        assertEquals("de-DE", VoiceLocaleTag.tag("de-DE"))
        assertEquals("ja", VoiceLocaleTag.tag("ja"))
    }

    @Test
    fun `a rider who never chose a voice still gets listened to`() {
        assertEquals(VoiceLocaleTag.FALLBACK, VoiceLocaleTag.tag(""))
        assertEquals(VoiceLocaleTag.FALLBACK, VoiceLocaleTag.tag("   "))
    }

    @Test
    fun `whitespace around a saved value does not break it`() {
        assertEquals("fr-FR", VoiceLocaleTag.tag("  fr_FR  "))
    }
}
