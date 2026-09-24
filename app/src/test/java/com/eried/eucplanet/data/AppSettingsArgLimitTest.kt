package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.VoiceReportSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Guard for the JVM's 255-parameter-slot limit on [AppSettings].
 *
 * Kotlin generates a static `copy$default` taking the receiver, every property,
 * one int bitmask per 32 properties, and a marker. Cross 255 slots and the class
 * fails to verify AT RUNTIME with an ART VerifyError: it compiles perfectly and
 * then crashes the moment anything calls `copy()`, which is every settings
 * write. Long and Double count as two slots each.
 *
 * This is the mechanism behind rule 8 in CLAUDE.md, and the reason 46 advanced
 * fields were moved into [com.eried.eucplanet.data.model.AdvancedSettings].
 *
 * If this test fails, do NOT delete a field to make room. Move a related group
 * into a nested data class, the way [VoiceReportSettings] does.
 */
class AppSettingsArgLimitTest {

    private fun copyDefaultSlots(): Int {
        val ctor = AppSettings::class.primaryConstructor
            ?: error("AppSettings must have a primary constructor")
        val params = ctor.parameters
        // Long and Double occupy two slots; everything else occupies one.
        val valueSlots: Int = params.fold(0) { acc, p ->
            acc + when (p.type.classifier) {
                Long::class, Double::class -> 2
                else -> 1
            }
        }
        val masks = (params.size + 31) / 32
        return 1 /* receiver */ + valueSlots + masks + 1 /* marker */
    }

    @Test fun copyDefault_staysUnderTheJvmParameterLimit() {
        val slots = copyDefaultSlots()
        assertTrue(
            "AppSettings.copy\$default needs $slots parameter slots, over the JVM's 255. " +
                "copy() will throw VerifyError at runtime. Move a group of fields into a " +
                "nested data class instead of adding another top-level one.",
            slots <= 255
        )
    }

    @Test fun theRemainingHeadroomIsStatedOutLoud() {
        // Not a correctness check: a deliberate tripwire. AppSettings sits ONE
        // slot under the JVM's 255, so anyone adding a field has to look at
        // this number and decide consciously rather than discover it in a
        // crash report. 253 was the voice cues nested as one group; 254 is the
        // watch map (PR #25), nested as WatchMapSettings so four fields cost
        // one slot. There is no room for another top-level field: the next
        // one goes into an existing nested group, or moves a group out.
        val expectedSlots = 254
        assertEquals(
            "AppSettings slot usage changed. Prefer nesting a group of fields over " +
                "spending headroom, and update this number deliberately.",
            expectedSlots, copyDefaultSlots()
        )
    }

    @Test fun voiceExtras_defaultsAreAllOff() {
        val v = VoiceReportSettings()
        assertEquals(false, v.periodicCurrent)
        assertEquals(false, v.periodicPower)
        assertEquals(false, v.triggerCurrent)
        assertEquals(false, v.triggerPower)
    }

    @Test fun voiceExtras_survivesACopy() {
        // The whole point of nesting: copy() must still work.
        val s = AppSettings().copy(
            voiceReports = VoiceReportSettings(periodicCurrent = true, triggerPower = true)
        )
        assertEquals(true, s.voiceReports.periodicCurrent)
        assertEquals(true, s.voiceReports.triggerPower)
        assertEquals(false, s.voiceReports.periodicPower)
    }
}
