package com.eried.eucplanet.audio

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.Test

/**
 * Pins the exact samples every engine renders for a fixed ride, so moving the
 * synth (or running it on another phone) cannot change a single bit of what
 * the rider hears. The ride goes through [EngineDynamics] with a seeded
 * random source, ticks at 10 Hz and 1024-frame buffers, as the producer
 * plays it. When a hash differs on purpose (the sound was meant to change),
 * paste the table the failure prints, for each platform.
 *
 * The phones get a table each. The two runtimes' maths functions (exp, sin,
 * ln) can differ in the last bit of a float, so about one engine
 * in four matches bit for bit and the rest differ by at most 1e-6, some
 * 120 dB under the engine's peak and far below 16-bit output. Measured by
 * dumping the same ride from both test runs; each table pins its own phone
 * exactly, so neither can drift.
 */
class EngineSynthGoldenTest {

    private val sr = 44100

    private fun ride(): List<RideStep> {
        val tick = 100L
        fun hold(n: Int, speed: Float, pwm: Float) = List(n) { RideStep(tick, speed, pwm) }
        fun ramp(n: Int, s0: Float, s1: Float, p0: Float, p1: Float) =
            List(n) { i -> val t = i.toFloat() / (n - 1); RideStep(tick, s0 + (s1 - s0) * t, p0 + (p1 - p0) * t) }
        return hold(5, 0f, 0f) + ramp(15, 0f, 40f, 20f, 85f) + hold(3, 40f, 0f) +
            ramp(10, 40f, 5f, 0f, 0f) + List(10) { i -> RideStep(tick, if (i % 4 < 2) 0.8f else -0.8f, 0f) }
    }

    /** FNV-1a over the raw bits of every sample. */
    private class Hash {
        private var h = -0x340d631b7bdddcdbL
        fun add(x: FloatArray, n: Int) {
            for (i in 0 until n) {
                var bits = x[i].toRawBits()
                repeat(4) {
                    h = (h xor (bits and 0xFF).toLong()) * 0x100000001b3L
                    bits = bits ushr 8
                }
            }
        }
        override fun toString() = h.toULong().toString(16)
    }

    private fun render(profile: EngineProfile): String {
        val model = EngineDynamics().apply {
            this.profile = profile
            pwmEverNonZero = true
            var seed = 12345L
            random = { seed = (seed * 6364136223846793005L + 1442695040888963407L); ((seed ushr 11).toDouble() / (1L shl 53).toDouble()) }
        }
        val loader = TestGrainBanks.loader(sr)
        // A recorded engine with no bank would fall back to the synth and still render.
        profile.grainAsset?.let { assertNotNull(loader(it), "${profile.key}: $it does not read") }
        val synth = EngineSynth(sr, loader)
        synth.preload(profile)
        val hash = Hash()
        val buf = FloatArray(1024)
        var now = 1_000L
        var due = 0.0
        for (step in ride()) {
            var params = model.tick(now, step.speedKmh, step.pwmPercent)
            due += step.holdMs * sr / 1000.0
            while (due >= 1.0) {
                val n = minOf(buf.size, due.toInt())
                synth.render(params, buf, 0, n)
                hash.add(buf, n)
                due -= n
                if (params.pendingPops > 0) params = params.copy(pendingPops = 0)
            }
            now += step.holdMs
        }
        // The settings preview's plain sweep goes straight in, no speed model.
        for (i in 0..20) {
            synth.render(model.direct(now, i / 20f, i / 20f * 0.7f), buf, 0, buf.size)
            hash.add(buf, buf.size)
            now += 50
        }
        return hash.toString()
    }

    @Test
    fun `every engine renders the same samples for the same ride`() {
        val actual = EngineProfile.PROFILES.associate { it.key to render(it) }
        val table = actual.entries.joinToString("\n") { (k, v) -> "            \"$k\" to \"$v\"," }
        val expected = if (goldenOnIos) EXPECTED_IOS else EXPECTED
        assertEquals(expected, actual, "rendered samples changed (ios=$goldenOnIos):\n$table\n")
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "TWO_STROKE" to "95c8b4d55c708bea",
            "FOUR_STROKE_SINGLE" to "f9765517406b9072",
            "QUAD_BIKE" to "31ade4ccf03d6610",
            "V_TWIN" to "ccb89616d2a6a622",
            "INLINE_4" to "748af0f62aa5bc79",
            "V8_MUSCLE" to "b424eeee0ff4a8b0",
            "V12_ITALIAN" to "2ce156589bac339a",
            "V16_LUXURY" to "c5850db080551b8a",
            "BOXER_6" to "13d2e30c1f79e2fb",
            "INLINE_6_SILKY" to "bdf47e54c19f18ed",
            "TRACTOR" to "b56c481b69629d42",
            "STEAM_LOCO" to "586beed43edcbc18",
            "TRON_WHINE" to "6729fab00e2a1e55",
            "POD_RACER" to "e2234d27b6d7a80b",
            "LIGHTSABER" to "9759d1241fbc8422",
            "JET_TURBINE" to "7bdaa8053c9a2d89",
            "UFO_HOVER" to "bc8a8795ec7d2b46",
            "F1_ELECTRIC" to "b6e2bc574c8d50b2",
            "HOVER_BIKE" to "352df6b092fde754",
            "SAMPLED_DIESEL_IVECO" to "9dd82f19fead504",
            "SAMPLED_BROKEN_EXHAUST" to "ef1a0b13a7f267bc",
            "SAMPLED_MOTORCYCLE" to "b4282a58d2581400",
            "SAMPLED_V8_COBRA" to "b424eeee0ff4a8b0",
            "SAMPLED_VTWIN_DUCATI" to "9ddcc6fe3a31aac8",
            "SAMPLED_CITY_CAR" to "ecd16dbf05dffd4f",
            "SAMPLED_STEAM_LOCO" to "586beed43edcbc18",
            "SAMPLED_ASTON_MARTIN" to "2ce156589bac339a",
        )

        val EXPECTED_IOS: Map<String, String> = mapOf(
            "TWO_STROKE" to "8be8d489f39b6f12",
            "FOUR_STROKE_SINGLE" to "b3213adcc4ed87b1",
            "QUAD_BIKE" to "aba967eee17ad5e4",
            "V_TWIN" to "d970dbf4b829057e",
            "INLINE_4" to "58b9044c2fd09a2",
            "V8_MUSCLE" to "b424eeee0ff4a8b0",
            "V12_ITALIAN" to "ea89017accdb4c9f",
            "V16_LUXURY" to "d71837cb58d9a14f",
            "BOXER_6" to "4a9d26144c3af2b3",
            "INLINE_6_SILKY" to "3bf206ce9c34283",
            "TRACTOR" to "aa936b45c28d4279",
            "STEAM_LOCO" to "586beed43edcbc18",
            "TRON_WHINE" to "6729fab00e2a1e55",
            "POD_RACER" to "b39e5ca928500268",
            "LIGHTSABER" to "9759d1241fbc8422",
            "JET_TURBINE" to "ef6104b8be1b824d",
            "UFO_HOVER" to "bc8a8795ec7d2b46",
            "F1_ELECTRIC" to "cd48d7c2f642ec8f",
            "HOVER_BIKE" to "463e5dfbbea19c37",
            "SAMPLED_DIESEL_IVECO" to "9dd82f19fead504",
            "SAMPLED_BROKEN_EXHAUST" to "ef1a0b13a7f267bc",
            "SAMPLED_MOTORCYCLE" to "b3821803954ade44",
            "SAMPLED_V8_COBRA" to "b424eeee0ff4a8b0",
            "SAMPLED_VTWIN_DUCATI" to "875afe5daa7141e",
            "SAMPLED_CITY_CAR" to "e01b12045fdfcb8a",
            "SAMPLED_STEAM_LOCO" to "586beed43edcbc18",
            "SAMPLED_ASTON_MARTIN" to "ea89017accdb4c9f",
        )
    }
}

/** True on the iOS simulator, where the golden table is the iPhone's. */
expect val goldenOnIos: Boolean
