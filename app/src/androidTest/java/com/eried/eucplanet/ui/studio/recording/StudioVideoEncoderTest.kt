package com.eried.eucplanet.ui.studio.recording

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device check for the Overlay Studio replay export bug: a clip's duration
 * must follow the intended timeline (frame count x per-frame duration), NOT the
 * wall-clock time the offline render happened to take. Before the fix a 1:15
 * trip exported as ~17 s because 240 frames encoded in ~17 s of wall clock and
 * the encoder stamped frames with that wall clock.
 *
 * Needs real MediaCodec / MediaMuxer / MediaExtractor, so it runs as an
 * instrumented test on the emulator rather than on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class StudioVideoEncoderTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun durationMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: -1L
        } finally {
            runCatching { r.release() }
        }
    }

    @Test
    fun targetDuration_setsClipLengthIndependentOfRenderTime() {
        val frames = 30
        val expectedMs = 3_000L // intended clip length
        val targetDurationUs = expectedMs * 1000L

        val enc = StudioVideoEncoder(context, withAudio = false)
        assertTrue("encoder failed to start", enc.start(320, 240, targetDurationUs = targetDurationUs))

        val renderStart = System.nanoTime()
        repeat(frames) { i ->
            val ok = enc.submitFrame { canvas ->
                // Vary the colour so the encoder produces real, differing frames.
                canvas.drawColor(if (i % 2 == 0) Color.rgb(20, 120, 200) else Color.rgb(200, 80, 40))
            }
            assertTrue("submitFrame $i failed", ok)
        }
        val renderMs = (System.nanoTime() - renderStart) / 1_000_000
        val uri = enc.finish()

        try {
            assertNotNull("finish() returned no uri", uri)
            val dur = durationMs(uri!!)
            // The clip lasts ~3 s (the timeline), well above the render time of a
            // few fast frames; the pre-fix bug would have produced ~renderMs.
            assertTrue("duration $dur ms not within tolerance of $expectedMs ms",
                dur in (expectedMs - 600)..(expectedMs + 600))
            assertTrue("duration $dur ms looks like render wall-time ($renderMs ms), not the timeline",
                dur > renderMs + 800)
        } finally {
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }
}
