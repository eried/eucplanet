package com.eried.eucplanet.crews

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What sits on the centre line of the crew pass screen, and what quietly does not.
 *
 * This card used to live in a scaffold that centred every child. It does not any more - the
 * card's body is a Column aligning to the start - and the change was made piecemeal, so each
 * thing that should be centred had to be told again, one at a time, by somebody noticing it on
 * a phone. The six-character code was caught first. `Step.Sending`'s spinner was caught second.
 * `Step.Confirming`'s spinner - the one a rider actually waits on, between scanning the code
 * and being asked to grant it - was not caught until Erwin opened the pass from a QR and saw it
 * against the left edge under a centred code.
 *
 * Three rounds of the same bug is a pattern, and the next composable added here will inherit
 * it. So this reads the source: every progress spinner on this screen must say where it sits,
 * and the code must keep the centring it was given.
 */
class CrewPassLayoutTest {

    private val src =
        File("src/main/java/com/eried/eucplanet/crews/CrewsPairActivity.kt").readText()

    @Test fun `every spinner on the pass screen is centred`() {
        // Each call, from its opening parenthesis to the matching close - spinners take a
        // modifier and nothing else here, so the first ')' at depth zero ends it.
        val spinners = mutableListOf<String>()
        var i = src.indexOf("CircularProgressIndicator(")
        while (i >= 0) {
            var depth = 0
            var j = i + "CircularProgressIndicator".length
            do {
                when (src[j]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                j++
            } while (depth > 0 && j < src.length)
            spinners.add(src.substring(i, j))
            i = src.indexOf("CircularProgressIndicator(", j)
        }

        assertTrue("no spinner found at all - has the screen been rewritten?", spinners.isNotEmpty())
        val adrift = spinners.filter { !it.contains("Alignment.CenterHorizontally") }
        assertTrue(
            "${adrift.size} of ${spinners.size} spinners do not say where they sit, so they " +
                "default to the start of the card and hang off its left edge:\n" +
                adrift.joinToString("\n") { it.replace(Regex("\\s+"), " ") },
            adrift.isEmpty(),
        )
    }

    @Test fun `the pass code stays on the centre line`() {
        // The one thing on this screen a rider reads against the browser, so it is the one
        // thing that must not drift back to the left edge.
        val code = src.substringAfter("s.link.code,").substringBefore("Spacer(")
        assertTrue(
            "the pass code no longer fills its width, so centring it has nothing to centre in",
            code.contains("fillMaxWidth()"),
        )
        assertTrue(
            "the pass code is no longer centred",
            code.contains("TextAlign.Center"),
        )
    }

    @Test fun `the code carries no optical nudge`() {
        // A 4dp start padding was once added here to compensate for the trailing letter-space,
        // the way the web page has to. Compose already trims it, so the nudge pushed the code
        // 4dp right of centre and was removed after being measured at +15px on a real screen.
        // The website needs that compensation and this does not; the two must not be made to
        // match each other by someone reading one and changing the other.
        val code = src.substringAfter("s.link.code,").substringBefore("Spacer(")
        assertTrue(
            "an optical nudge is back on the pass code; Compose trims the trailing " +
                "letter-space itself, so this moves the code OFF centre",
            !code.contains("padding(start"),
        )
    }
}
