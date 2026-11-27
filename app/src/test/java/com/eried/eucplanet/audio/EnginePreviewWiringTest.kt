package com.eried.eucplanet.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A Settings preview borrowed the engine and ended with stop(), whoever had it
 * before. With a wheel connected that silenced the ride's engine sound until the
 * next reconnect, and during the preview the wheel's telemetry and the preview's
 * script both drove the engine. The engine owns an AudioTrack and cannot be built
 * in a unit test, so the three things that keep this right are read from its source.
 */
class EnginePreviewWiringTest {
    private val src = File("../shared/src/commonMain/kotlin/com/eried/eucplanet/audio/EngineSoundEngine.kt").readText()
    private val preview = src.substringAfter("fun previewProfile(").substringBefore("\n    private fun pushParamsDirect")

    @Test
    fun `the wheel's telemetry waits while a preview plays`() {
        val push = src.substringAfter("fun pushTelemetry(").substringBefore("\n    }")
        assertTrue("pushTelemetry does not stand aside for a preview", push.contains("if (previewing) return"))
        // The preview's own steps must not go through the gate it closes.
        assertTrue("the preview feeds itself through pushTelemetry", !preview.contains("pushTelemetry("))
    }

    @Test
    fun `the ride's engine sound comes back after a preview`() {
        val after = preview.substringAfter("} finally {")
        assertTrue("no finally block in the preview", after != preview)
        assertTrue("the preview does not hand the engine back", after.contains("previewing = false"))
        assertTrue("the engine is not restarted for a connected wheel", after.contains("if (connected && lastAppliedEnabled) start()"))
        assertTrue("the gate opens before the motion is reset", after.indexOf("model.resetMotion()") < after.indexOf("previewing = false"))
    }

    @Test
    fun `a preview starts clean over a riding engine`() {
        // The shared engine runs the preview on a coroutine where Android had a Thread.
        val body = preview.substringAfter("previewScope.launch {").substringBefore("} finally {")
        assertTrue("the gate is not closed first", body.trimStart().startsWith("previewing = true"))
        assertTrue("a running engine is not stopped before the preview starts", body.contains("if (running) stop()"))
    }
}
