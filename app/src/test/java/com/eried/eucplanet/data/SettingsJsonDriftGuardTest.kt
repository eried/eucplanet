package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Drift guard for the hand-rolled [SettingsJson] mapper. Adding a field to
 * [AppSettings] without adding it to BOTH [SettingsJson.toJson] and
 * [SettingsJson.fromJson] makes the field silently unsaveable: every write
 * drops it and every read returns the default, so its toggle snaps back in
 * the UI. This test mutates every simple field away from its default, does a
 * full JSON round trip, and fails with the exact list of dropped fields.
 */
class SettingsJsonDriftGuardTest {

    // Legacy Room row id, intentionally not serialized.
    private val exempt = setOf("id")

    /**
     * Nested groups whose contents hold lists or spec-driven maps this guard
     * cannot mutate generically. Both already have dedicated tests.
     */
    private val nestedExempt = setOf("settingsLayout", "advanced")

    @Test
    fun everySimpleFieldSurvivesRoundTrip() {
        val defaults = AppSettings()
        val ctor = AppSettings::class.primaryConstructor
            ?: error("AppSettings must have a primary constructor")
        val props = AppSettings::class.memberProperties.associateBy { it.name }

        val mutations = mutableMapOf<String, Any>()
        val args = ctor.parameters.associateWith { param ->
            val name = param.name ?: return@associateWith null
            val current = props.getValue(name).get(defaults)
            if (name in exempt) return@associateWith current
            val mutated: Any? = when (current) {
                is Boolean -> !current
                is Int -> current + 1
                is Long -> current + 1L
                is Float -> current + 1f
                is Double -> current + 1.0
                is String -> current + "x"
                else -> null // nested/nullable/complex fields have their own tests
            }
            if (mutated != null) {
                mutations[name] = mutated
                mutated
            } else current
        }

        val candidate = ctor.callBy(args)
        val roundTripped = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(candidate).toString()))

        val dropped = mutations.entries.mapNotNull { (name, expected) ->
            val actual = props.getValue(name).get(roundTripped)
            if (actual != expected) "$name: wrote $expected, read back $actual" else null
        }
        if (dropped.isNotEmpty()) {
            fail(
                "SettingsJson drops or mangles these AppSettings fields, add them to " +
                    "toJson() AND fromJson():\n" + dropped.joinToString("\n")
            )
        }
    }

    /**
     * The same guard, one level down.
     *
     * The test above only mutates simple fields and hands nested groups
     * straight back, on the promise that each has a test of its own.
     * TpmsSettings did not, and the whole group was missing from both halves
     * of the mapper: a paired sensor was forgotten on every restart and a
     * rider who chose psi got bar back, while every field looked correctly
     * wired on the model. A group nobody remembered to write a test for is
     * exactly the group that needs this one.
     */
    @Test
    fun everyNestedGroupSurvivesRoundTrip() {
        val defaults = AppSettings()
        val outerProps = AppSettings::class.memberProperties.associateBy { it.name }
        val dropped = mutableListOf<String>()

        for ((groupName, outerProp) in outerProps) {
            if (groupName in exempt || groupName in nestedExempt) continue
            val group = outerProp.get(defaults) ?: continue
            val klass = group::class
            if (!klass.isData) continue
            val ctor = klass.primaryConstructor ?: continue
            val props = klass.memberProperties.associateBy { it.name }

            val mutations = mutableMapOf<String, Any>()
            val args = ctor.parameters.associateWith { param ->
                val name = param.name ?: return@associateWith null
                val current = props.getValue(name).getter.call(group)
                val mutated: Any? = when (current) {
                    is Boolean -> !current
                    is Int -> current + 1
                    is Long -> current + 1L
                    is Float -> current + 1f
                    is Double -> current + 1.0
                    is String -> current + "x"
                    // A null String is the interesting case: pairedAddress is
                    // null until a rider pairs something, so leaving it alone
                    // would have tested nothing at all.
                    null -> if (param.type.classifier == String::class) "x" else null
                    else -> null
                }
                if (mutated != null) {
                    mutations[name] = mutated
                    mutated
                } else current
            }
            if (mutations.isEmpty()) continue

            val outerCtor = AppSettings::class.primaryConstructor!!
            val candidate = outerCtor.callBy(
                mapOf(outerCtor.parameters.first { it.name == groupName } to ctor.callBy(args))
            )
            val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(candidate).toString()))
            val backGroup = outerProp.get(back)!!
            for ((name, expected) in mutations) {
                val actual = props.getValue(name).getter.call(backGroup)
                if (actual != expected) {
                    dropped += "$groupName.$name: wrote $expected, read back $actual"
                }
            }
        }

        if (dropped.isNotEmpty()) {
            fail(
                "SettingsJson drops or mangles these nested settings, add the group to " +
                    "toJson() AND fromJson():\n" + dropped.joinToString("\n")
            )
        }
    }
}
