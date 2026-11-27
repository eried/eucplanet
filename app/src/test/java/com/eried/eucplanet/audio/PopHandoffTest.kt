package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The audio thread renders the latest snapshot once per buffer, several times
 * between two telemetry ticks. A pop left in the snapshot was laid again on every
 * one of them: a burst whose length followed how slowly the wheel reports, the
 * same recording restarted so fast it buzzed.
 */
class PopHandoffTest {
    private val withPop = EngineParams(rpm = 2000f, pendingPops = 1)
    private val plain = EngineParams(rpm = 2100f)

    @Test
    fun `a pop is played by one buffer, however many the tick spans`() {
        val handoff = PopHandoff()
        val snapshot = handoff.post(withPop)
        val played = (1..8).sumOf { handoff.take(snapshot).pendingPops }
        assertEquals(1, played)
    }

    @Test
    fun `the snapshot the audio thread keeps reading carries no pop`() {
        val snapshot = PopHandoff().post(withPop)
        assertEquals(0, snapshot.pendingPops)
        assertEquals(2000f, snapshot.rpm, 0f)
    }

    @Test
    fun `a pop survives a newer tick that lands before the audio thread comes`() {
        val handoff = PopHandoff()
        handoff.post(withPop)
        val newer = handoff.post(plain)
        val heard = handoff.take(newer)
        assertEquals(1, heard.pendingPops)
        assertEquals(2100f, heard.rpm, 0f)
    }

    @Test
    fun `pops that piled up while the audio was held up play as one`() {
        val handoff = PopHandoff()
        handoff.post(withPop)
        val snapshot = handoff.post(withPop)
        assertEquals(1, handoff.take(snapshot).pendingPops)
        assertEquals(0, handoff.take(snapshot).pendingPops)
    }

    @Test
    fun `a pop nobody played does not go off when the audio starts later`() {
        val handoff = PopHandoff()
        val snapshot = handoff.post(withPop)
        handoff.clear()
        assertEquals(0, handoff.take(snapshot).pendingPops)
    }

    /** The engine cannot be built in a unit test (it owns an AudioTrack), so its wiring is read. */
    @Test
    fun `the engine hands every snapshot through it`() {
        val src = File("../shared/src/commonMain/kotlin/com/eried/eucplanet/audio/EngineSoundEngine.kt").readText()
        val writes = Regex("paramsSnapshot = (.*)").findAll(src).map { it.groupValues[1].trim() }.toList()
        assertTrue("no snapshot writes found", writes.size >= 2)
        for (w in writes) assertTrue("snapshot written past the handoff: $w", w.startsWith("pops.post("))
        assertTrue("the audio thread reads the snapshot past the handoff", src.contains("pops.take(paramsSnapshot)"))
        val start = src.substringAfter("fun start() {").substringBefore("fun stop()")
        assertTrue("start() does not drop a stale pop", start.contains("pops.clear()"))
    }
}
