package com.eried.eucplanet.voice

import com.eried.eucplanet.data.model.MetricCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every catalog metric a rider can say is either answerable or listed as not.
 *
 * The feature offers the whole metric catalog as things to ask for, and the
 * "what can I say" list prints them, so the promise is the catalog. The value
 * comes from somewhere else entirely, and the two drifted: thirteen metrics
 * were offered and answered "No X yet" forever, whatever the wheel was doing.
 * A rider asking for the headlight got that, while "lights on" worked, which
 * is the shape of the bug that surfaced it.
 *
 * This is the guard, in the rule 13 sense: a metric added to the catalog
 * tomorrow either has a way to be read or is written into
 * [VOICE_CANNOT_READ] on purpose, with the reason beside it.
 */
class VoiceMetricCoverageTest {

    @Test fun `no metric is offered without either a value or an admission`() {
        val unaccounted = MetricCatalog.all
            .map { it.key }
            .filter { !voiceCanRead(it) && it !in VOICE_CANNOT_READ }
        assertTrue(
            "These metrics are in the catalog, so a rider can say them and the " +
                "list prints them, but nothing can produce a value: $unaccounted. " +
                "Give them a reader or add them to VOICE_CANNOT_READ with the reason.",
            unaccounted.isEmpty(),
        )
    }

    @Test fun `the admission list stays honest`() {
        // A key that gained a reader should leave the list rather than sit
        // there claiming it cannot be answered.
        val nowReadable = VOICE_CANNOT_READ.filter { voiceCanRead(it) }
        assertTrue(
            "These are listed as unreadable but can now be read: $nowReadable",
            nowReadable.isEmpty(),
        )
        // And a key that left the catalog should not linger either.
        val keys = MetricCatalog.all.map { it.key }.toSet()
        val stale = VOICE_CANNOT_READ.filter { it !in keys }
        assertTrue("These are no longer catalog metrics: $stale", stale.isEmpty())
    }

    @Test fun `the headlight answers its state rather than a number`() {
        // The bug this file was written for. LIGHT_ON is a boolean on the
        // packet, so the numeric extractors never saw it and every ask fell
        // through to "no data". It reads as a word now.
        assertTrue("LIGHT_ON has no reader", voiceCanRead("LIGHT_ON"))
        assertTrue("LIGHT_ON should not be listed as unreadable",
            "LIGHT_ON" !in VOICE_CANNOT_READ)
    }

    @Test fun `the ride mode does too`() {
        assertTrue("PC_MODE has no reader", voiceCanRead("PC_MODE"))
        assertTrue("PC_MODE should not be listed as unreadable",
            "PC_MODE" !in VOICE_CANNOT_READ)
    }

    @Test fun `the gap is the size it is known to be`() {
        // Deliberately a number, so shrinking it is a visible, intended edit
        // rather than something that happens by accident.
        assertEquals(11, VOICE_CANNOT_READ.size)
    }
}
