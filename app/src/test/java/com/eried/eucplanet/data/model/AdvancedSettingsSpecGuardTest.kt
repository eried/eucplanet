package com.eried.eucplanet.data.model

import com.eried.eucplanet.data.repository.sanitized
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Drift guard for the Advanced settings registry.
 *
 * Every [AdvancedSettings] field must have exactly one [AdvancedSpec], and
 * every spec must read and write a real field, or the knob is either
 * invisible in the UI and never clamped, or clamps the wrong thing. The
 * field a spec targets is found by probing: its `set` is applied to the
 * defaults and the one property that changed is the target; `get` must then
 * read that value back for two different probes so it cannot be reading a
 * neighbour that happens to hold the same number. The spec id is also the
 * JSON key, so it has to be the field name as well.
 *
 * The second test is the only place `sanitized()` runs under test: every
 * Advanced value far above and far below its range must come back clamped
 * to the range's ends.
 */
class AdvancedSettingsSpecGuardTest {

    private val ctor = AdvancedSettings::class.primaryConstructor
        ?: error("AdvancedSettings must have a primary constructor")
    private val props: Map<String, KProperty1<AdvancedSettings, *>> =
        AdvancedSettings::class.memberProperties.associateBy { it.name }
    private val fieldNames = ctor.parameters.mapNotNull { it.name }.toSet()

    /** The constructor field a spec's set() writes and its get() reads. */
    private fun fieldTargetedBy(spec: AdvancedSpec): String {
        val defaults = ADVANCED_DEFAULTS
        val first = spec.default() + 1
        val second = spec.default() + 2
        val probe1 = spec.set(defaults, first)
        val probe2 = spec.set(defaults, second)
        val changed = props.values
            .filter { it.get(probe1) != it.get(defaults) }
            .map { it.name }
        if (changed.size != 1) {
            fail("spec '${spec.id}': set() changed $changed, expected exactly one field")
        }
        val field = changed.single()
        assertEquals("spec '${spec.id}': set() wrote $first to $field", first, props.getValue(field).get(probe1))
        assertEquals("spec '${spec.id}': get() reads the field set() writes", first, spec.get(probe1))
        assertEquals("spec '${spec.id}': get() reads the field set() writes", second, spec.get(probe2))
        return field
    }

    @Test
    fun everyFieldHasExactlyOneSpecAndEverySpecTargetsAField() {
        val targets = ADVANCED_SPECS.associateWith { fieldTargetedBy(it) }

        val misnamed = targets.filter { (spec, field) -> spec.id != field }
            .map { (spec, field) -> "'${spec.id}' targets $field" }
        if (misnamed.isNotEmpty()) {
            fail("spec id is the JSON key and must equal its field name:\n" + misnamed.joinToString("\n"))
        }

        val specsPerField = targets.values.groupingBy { it }.eachCount()
        val missing = fieldNames - specsPerField.keys
        val duplicated = specsPerField.filter { it.value > 1 }.keys
        val unknown = specsPerField.keys - fieldNames
        if (missing.isNotEmpty() || duplicated.isNotEmpty() || unknown.isNotEmpty()) {
            fail(
                "ADVANCED_SPECS drifted from AdvancedSettings' constructor.\n" +
                    "Fields with no spec: $missing\n" +
                    "Fields with more than one spec: $duplicated\n" +
                    "Specs targeting no field: $unknown"
            )
        }
        assertEquals("one spec per field", fieldNames.size, ADVANCED_SPECS.size)
        assertEquals("spec ids are unique", ADVANCED_SPECS.size, ADVANCED_SPECS.map { it.id }.toSet().size)
    }

    @Test
    fun sanitizedClampsEveryAdvancedValueIntoItsRange() {
        val tooHigh = ADVANCED_SPECS.fold(AdvancedSettings()) { a, s -> s.set(a, s.range.last * 10) }
        val tooLow = ADVANCED_SPECS.fold(AdvancedSettings()) { a, s -> s.set(a, s.range.first - 1000) }

        val high = AppSettings(advanced = tooHigh).sanitized().advanced
        val low = AppSettings(advanced = tooLow).sanitized().advanced

        val unclamped = ADVANCED_SPECS.flatMap { s ->
            listOfNotNull(
                s.get(high).takeIf { it != s.range.last }
                    ?.let { "${s.id}: ${s.range.last * 10} came back $it, expected ${s.range.last}" },
                s.get(low).takeIf { it != s.range.first }
                    ?.let { "${s.id}: ${s.range.first - 1000} came back $it, expected ${s.range.first}" },
            )
        }
        if (unclamped.isNotEmpty()) {
            fail("sanitized() left these Advanced values outside their spec range:\n" + unclamped.joinToString("\n"))
        }

        // Defaults sit inside every range and pass through untouched.
        assertEquals(AdvancedSettings(), AppSettings().sanitized().advanced)
    }
}
