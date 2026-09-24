package com.eried.eucplanet.data.model

import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Drift guard for the [ADVANCED_SPECS] registry. The specs each carry a
 * hand-written get/set lambda, so the real risk is a copy-paste error
 * (get reading, or set writing, the wrong field). These tests catch that, plus
 * duplicate ids and out-of-range defaults.
 */
class AdvancedSpecTest {

    @Test
    fun `spec ids are unique`() {
        val ids = ADVANCED_SPECS.map { it.id }
        assertEquals("duplicate spec ids: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}",
            ids.size, ids.toSet().size)
    }

    @Test
    fun `every spec get-set round-trips and never leaks into another field`() {
        val base = AdvancedSettings()
        for (spec in ADVANCED_SPECS) {
            val cur = spec.get(base)
            // A different, in-range probe value (step up, or down if at the ceiling).
            val probe = (cur + spec.step).coerceIn(spec.range).let {
                if (it != cur) it else (cur - spec.step).coerceIn(spec.range)
            }
            assertNotEquals("range too small to test ${spec.id}", cur, probe)

            val updated = spec.set(base, probe)
            assertEquals("${spec.id}: get(set(v)) != v — wrong field in get or set", probe, spec.get(updated))

            for (other in ADVANCED_SPECS) {
                if (other.id == spec.id) continue
                assertEquals("setting ${spec.id} changed ${other.id} — set copies the wrong field",
                    other.get(base), other.get(updated))
            }
        }
    }
    @Test
    fun `all advanced specs survive one JSON round trip`() {
        val candidate = ADVANCED_SPECS.fold(AdvancedSettings()) { settings, spec ->
            val current = spec.get(settings)
            val probe = (current + spec.step).coerceIn(spec.range).let {
                if (it != current) it else (current - spec.step).coerceIn(spec.range)
            }
            spec.set(settings, probe)
        }
        val restored = SettingsJson.fromJson(
            JSONObject(SettingsJson.toJson(AppSettings(advanced = candidate)).toString()),
        ).advanced
        ADVANCED_SPECS.forEach { spec ->
            assertEquals(spec.id, spec.get(candidate), spec.get(restored))
        }
    }


    @Test
    fun `defaults are within their spec range`() {
        for (spec in ADVANCED_SPECS) {
            val d = spec.get(ADVANCED_DEFAULTS)
            assertTrue("${spec.id} default $d out of range ${spec.range}", d in spec.range)
        }
    }

    @Test
    fun `every spec formats its default to a non-blank string`() {
        for (spec in ADVANCED_SPECS) {
            assertTrue("${spec.id} formats blank", spec.format(spec.get(ADVANCED_DEFAULTS)).isNotBlank())
        }
    }
}
