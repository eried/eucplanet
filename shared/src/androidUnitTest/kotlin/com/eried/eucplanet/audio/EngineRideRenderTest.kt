package com.eried.eucplanet.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records every engine, recorded and synthesised, riding [EngineRide.fullRide] to a WAV, for
 * listening and for the spectrum analysis in tools/engine-sounds. Runs only
 * when EUC_ENGINE_RENDER_DIR names the output folder:
 *
 *   EUC_ENGINE_RENDER_DIR=... ./gradlew :shared:testDebugUnitTest --tests "*EngineRideRenderTest"
 *
 * The same model and synth the phone runs; the ticks come at 10 Hz and the
 * synth renders 1024-frame buffers from the latest snapshot, like the
 * producer thread does.
 */
class EngineRideRenderTest {

    private val sampleRate = 44100
    private val bufferFrames = 1024

    @Test
    fun `render every procedural engine through the ride`() {
        val dirName = System.getenv("EUC_ENGINE_RENDER_DIR")
        assumeTrue("EUC_ENGINE_RENDER_DIR not set", !dirName.isNullOrBlank())
        val dir = File(dirName!!).apply { mkdirs() }
        val only = System.getenv("EUC_ENGINE_ONLY")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val phases = EngineRide.fullRide()
        File(dir, "phases.txt").writeText(phases.joinToString("\n") { "${it.startMs / 1000f}s ${it.name}" } + "\n")
        val profiles = EngineProfile.PROFILES.filter { only == null || it.key in only }
        for (p in profiles) {
            val pcm = render(p, phases)
            writeWav(File(dir, "${p.key.lowercase()}.wav"), pcm)
        }
        File(dir, "engines.txt").writeText(profiles.joinToString("\n") {
            "${it.key}\t${it.displayName}\t${if (it.grainAsset != null) "RECORDED" else it.kind.toString()}\t${if (it.preview) "hidden" else "listed"}"
        } + "\n")
    }

    private fun render(profile: EngineProfile, phases: List<EngineRide.Phase>): FloatArray {
        val model = EngineDynamics().apply {
            this.profile = profile
            pwmEverNonZero = true
            var seed = 12345L
            random = { seed = (seed * 6364136223846793005L + 1442695040888963407L); ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) }
        }
        val synth = EngineSynth(sampleRate, TestGrainBanks.loader(sampleRate))
        val steps = phases.flatMap { it.steps }
        val totalMs = steps.sumOf { it.holdMs }
        val totalFrames = (totalMs * sampleRate / 1000).toInt()
        val out = FloatArray(totalFrames)
        val buf = FloatArray(bufferFrames)
        var now = 0L
        var frame = 0
        var params = EngineParams.SILENT
        for (step in steps) {
            params = model.tick(now, step.speedKmh, step.pwmPercent)
            val until = ((now + step.holdMs) * sampleRate / 1000).toInt().coerceAtMost(totalFrames)
            while (frame < until) {
                val n = minOf(bufferFrames, until - frame)
                synth.render(params, buf, 0, n)
                System.arraycopy(buf, 0, out, frame, n)
                frame += n
                // Pops are consumed by the first buffer of a tick.
                if (params.pendingPops > 0) params = params.copy(pendingPops = 0)
            }
            now += step.holdMs
        }
        return out
    }

    private fun writeWav(file: File, pcm: FloatArray) {
        val bytes = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(pcm.size * 2)
        for (s in pcm) bytes.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(bytes.array()) }
    }
}
