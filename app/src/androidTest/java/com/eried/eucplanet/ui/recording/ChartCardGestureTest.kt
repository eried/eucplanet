package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trip chart's three gestures, on a real touch screen.
 *
 * One canvas carries a long-press scrub, a two-finger zoom and the page's
 * scroll. A long press places the read; a pinch turns around the fingers.
 * Neither can be driven from adb, which has no multitouch at all, so they are
 * pinned here.
 */
@RunWith(AndroidJUnit4::class)
class ChartCardGestureTest {

    @get:Rule
    val rule = createComposeRule()

    private val n = 400
    private val values = List(n) { i -> 20f + (i % 40) }

    private val window = mutableStateOf(0f..1f)
    private val scrub = mutableStateOf<Int?>(null)

    private fun content() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.testTag("chart")) {
                    ChartCard(
                        title = "Speed",
                        values = values,
                        color = Color.Red,
                        unitLabel = "km/h",
                        minSpan = 1f,
                        scrubIndex = scrub.value,
                        onScrub = { scrub.value = it },
                        window = window.value,
                        onWindow = { window.value = it },
                        onWindowCommit = {},
                        onResetView = {},
                    )
                }
            }
        }
    }

    /** Long-press and hold at [frac] across the chart, placing a read there. */
    private fun placeRead(frac: Float) {
        rule.onNodeWithTag("chart").performTouchInput {
            down(Offset(width * frac, centerY))
            advanceEventTime(900)
            moveTo(Offset(width * frac, centerY))
            advanceEventTime(50)
            up()
        }
        rule.waitForIdle()
    }

    /**
     * A two-finger pinch centred at [atFrac], opening by [steps] * 25px per
     * finger, with the sideways drift that any real pair of fingers has.
     */
    private fun pinchOpen(atFrac: Float, steps: Int = 10) {
        rule.onNodeWithTag("chart").performTouchInput {
            val y = centerY
            val c = width * atFrac
            var a = c - 60f
            var b = c + 60f
            down(0, Offset(a, y))
            down(1, Offset(b, y))
            advanceEventTime(16)
            repeat(steps) {
                // Apart (the zoom) plus a shared shove to the right (the drift).
                a += -25f + 8f
                b += 25f + 8f
                updatePointerTo(0, Offset(a, y))
                updatePointerTo(1, Offset(b, y))
                move()
                advanceEventTime(16)
            }
            up(0)
            up(1)
        }
        rule.waitForIdle()
    }

    private fun onScreenFracOf(index: Int): Float {
        val w = window.value
        val span = w.endInclusive - w.start
        return (index / (n - 1).toFloat() - w.start) / span
    }

    @Test
    fun aLongPressPlacesTheRead() {
        content()
        placeRead(0.25f)
        val read = scrub.value
        assertNotNull("a long press should place a read", read)
        assertEquals(0.25f, read!! / (n - 1).toFloat(), 0.03f)
    }

    @Test
    fun withNoReadThePinchTurnsAroundTheFingers() {
        content()
        // Nothing being read: the ordinary gesture, anchored on the centroid.
        pinchOpen(0.30f)
        val w = window.value
        // Only that it zoomed: the fingers reach the node's edge here, so how
        // far it got is a property of the harness, not of the gesture.
        assertTrue("the pinch should have zoomed in, window was $w", (w.endInclusive - w.start) < 0.99f)
        val underFingers = w.start + (w.endInclusive - w.start) * 0.30f
        assertEquals(
            "the point under the fingers stays under them",
            0.30f, underFingers, 0.08f,
        )
    }
}
