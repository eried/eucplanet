package com.eried.eucplanet.voice

import com.eried.eucplanet.voice.VoiceCommandMatcher.VoiceMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outside air and the motor are both "temperature".
 *
 * A rider on a hot climb who says "temperature" means the wheel, and that is
 * the older meaning and the one they will reach for. So the weather's
 * temperature had to take a longer name, and the separation rests on the
 * matcher preferring the longest name that fits rather than on any rule
 * written down in one place. Same shape as the light toggle and its status,
 * and the same reason to pin it.
 */
class VoiceWeatherTest {

    private val vocabulary = VoiceVocabulary.build(
        metricNames = mapOf(
            // The wheel's own, which has always answered to the bare word.
            "TEMPERATURE" to "Temperature",
            "MOTOR_TEMP" to "Motor temperature",
            "SPEED" to "Speed",
        ),
        reportNames = emptyMap(),
        splitName = "last split",
        helpPhrases = "help,what can I say",
        specialPhrases = mapOf(
            VoiceVocabulary.Special.WEATHER to "weather,good day to ride,is it a good day",
            VoiceVocabulary.Special.AIR_TEMP to
                "air temperature,outside temperature,how cold is it",
            VoiceVocabulary.Special.WIND to "wind,wind speed,how windy",
            VoiceVocabulary.Special.HUMIDITY to "humidity,how humid",
        ),
        actionPhrases = emptyMap(),
    )

    private fun key(said: String): String? =
        (VoiceCommandMatcher.match(said, vocabulary, emptySet()) as? VoiceMatch.Hit)?.term?.key

    @Test fun `the bare word is still the wheel`() {
        // The older meaning wins the shorter phrase. A rider asking about a
        // hot motor must not be told the air is pleasant.
        assertEquals("TEMPERATURE", key("temperature"))
    }

    @Test fun `the air needs its own words`() {
        assertEquals(VoiceVocabulary.Special.AIR_TEMP, key("air temperature"))
        assertEquals(VoiceVocabulary.Special.AIR_TEMP, key("outside temperature"))
    }

    @Test fun `the motor keeps its own words too`() {
        // Three things now end in "temperature". None may swallow another.
        assertEquals("MOTOR_TEMP", key("motor temperature"))
    }

    @Test fun `wind and humidity answer on their own`() {
        assertEquals(VoiceVocabulary.Special.WIND, key("wind"))
        assertEquals(VoiceVocabulary.Special.WIND, key("wind speed"))
        assertEquals(VoiceVocabulary.Special.HUMIDITY, key("humidity"))
    }

    @Test fun `none of the weather questions is ambiguous`() {
        // Ambiguity asks the rider a question back, which at speed is worse
        // than either answer.
        val said = listOf(
            "weather", "air temperature", "outside temperature", "how cold is it",
            "wind", "wind speed", "how windy", "humidity", "how humid",
            "temperature", "motor temperature",
        )
        for (phrase in said) {
            val m = VoiceCommandMatcher.match(phrase, vocabulary, emptySet())
            assertTrue("\"$phrase\" came back as $m", m is VoiceMatch.Hit)
        }
    }

    @Test fun `every weather question answers with no wheel connected`() {
        // The forecast is about the world. A rider standing in the hall
        // deciding whether to go out has nothing connected yet, and that is
        // exactly when they ask.
        for (k in listOf(
            VoiceVocabulary.Special.WEATHER,
            VoiceVocabulary.Special.AIR_TEMP,
            VoiceVocabulary.Special.WIND,
            VoiceVocabulary.Special.HUMIDITY,
        )) {
            assertTrue("$k is gated on a wheel", k in OFF_WHEEL)
        }
    }

    @Test fun `the wheel's own temperature is still gated on a wheel`() {
        // The other half of the same rule: a disconnected wheel has no motor
        // temperature, and "0 degrees" is the answer this must never give.
        assertTrue("TEMPERATURE" !in OFF_WHEEL)
    }
}
