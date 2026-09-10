package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.AlarmRule
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Drift guard for the alarm half of the settings backup.
 *
 * [AlarmBackupJson] writes each [AlarmRule] field by hand, so a field added
 * to the entity but not to the mapper is silently dropped from every backup
 * and comes back at its default on restore. This test mutates every
 * constructor field away from its default, round-trips through JSON and
 * fails with the exact list of fields that did not survive. A second test
 * checks the JSON key set against the constructor so the failure names the
 * missing key even before a value is compared.
 */
class AlarmBackupRoundTripTest {

    /** The Room row id is assigned on insert, never backed up. */
    private val exempt = setOf("id")

    private val ctor = AlarmRule::class.primaryConstructor
        ?: error("AlarmRule must have a primary constructor")
    private val props: Map<String, KProperty1<AlarmRule, *>> =
        AlarmRule::class.memberProperties.associateBy { it.name }

    /** Every field set to something other than its default, id left alone. */
    private fun everyFieldMutated(): AlarmRule {
        val defaults = AlarmRule()
        val args = ctor.parameters.associateWith { param ->
            val name = param.name ?: error("unnamed constructor parameter")
            val current = props.getValue(name).get(defaults)
            if (name in exempt) return@associateWith current
            when (current) {
                is Boolean -> !current
                is Int -> current + 1
                is Long -> current + 1L
                is Float -> current + 1f
                is Double -> current + 1.0
                is String -> current + "_x"
                null -> when (param.type.classifier) {
                    String::class -> "bound_$name"
                    else -> error("AlarmRule.$name: nullable ${param.type} needs a mutation rule here")
                }
                else -> error("AlarmRule.$name: ${current::class} needs a mutation rule here")
            }
        }
        return ctor.callBy(args)
    }

    @Test
    fun everyFieldSurvivesRoundTrip() {
        val rule = everyFieldMutated()
        val json = AlarmBackupJson.alarmsToJson(listOf(rule))
        // Re-parse from text, the way a restore reads it back from disk.
        val restored = AlarmBackupJson.jsonToAlarms(JSONArray(json.toString()))
        assertEquals("one rule in, one rule out", 1, restored.size)

        val dropped = props.values
            .filter { it.name !in exempt }
            .mapNotNull { p ->
                val expected = p.get(rule)
                val actual = p.get(restored.single())
                if (expected != actual) "${p.name}: wrote $expected, read back $actual" else null
            }
        if (dropped.isNotEmpty()) {
            fail(
                "AlarmBackupJson drops or mangles these AlarmRule fields, add them to " +
                    "alarmsToJson() AND jsonToAlarms():\n" + dropped.joinToString("\n")
            )
        }
        assertEquals(rule, restored.single().copy(id = rule.id))
    }

    @Test
    fun jsonKeysCoverEveryConstructorField() {
        val rule = everyFieldMutated()
        val obj = AlarmBackupJson.alarmsToJson(listOf(rule)).getJSONObject(0)
        val keys = obj.keys().asSequence().toSet()
        val fields = ctor.parameters.mapNotNull { it.name }.filter { it !in exempt }.toSet()
        val missing = fields - keys
        val unknown = keys - fields
        if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            fail(
                "AlarmBackupJson key set drifted from AlarmRule's constructor.\n" +
                    "Fields with no JSON key: $missing\n" +
                    "JSON keys with no field: $unknown"
            )
        }
    }
}
