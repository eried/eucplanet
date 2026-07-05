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
}
