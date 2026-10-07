package com.eried.eucplanet.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift guard for the theme token registry.
 *
 * [ThemeTokens.specs] is what the in-app theme editor lists and what the
 * colour identifier searches, and it is hand-maintained beside the
 * [AppThemeColors] fields. A colour added to the data class but not to the
 * registry is invisible to both: it cannot be themed, and the identifier
 * cannot name it. That is exactly how the forecast panel ended up drawing
 * humidity and the precipitation bars from one borrowed token.
 *
 * Reflection over the declared fields rather than a hand-copied list, so the
 * guard cannot drift the way the thing it guards did.
 */
class ThemeTokenRegistryTest {

    /** Fields that are not colours, or are structural rather than themeable. */
    private val notTokens = setOf("isLight")

    private fun colorFieldNames(): List<String> =
        AppThemeColors::class.java.declaredFields
            // Color is a value class over a long, so the backing fields are
            // primitives; name is the reliable discriminator.
            .filter { !it.isSynthetic && it.name !in notTokens }
            .map { it.name.substringBefore('$') }
            .filter { it.isNotBlank() }
            .distinct()

    @Test fun `the reflection actually sees the fields`() {
        // Without this the guards below pass vacuously if the field scan ever
        // returns nothing, which is the one way a drift guard silently dies.
        val names = colorFieldNames()
        assertTrue("only ${names.size} fields found: $names", names.size > 40)
        assertTrue("metricTemp not seen: $names", "metricTemp" in names)
        assertTrue("weatherHumidity not seen: $names", "weatherHumidity" in names)
    }

    @Test fun `every colour field has a registry spec`() {
        val specKeys = ThemeTokens.specs.map { it.key }.toSet()
        val missing = colorFieldNames().filter { it !in specKeys }
        assertTrue(
            "AppThemeColors fields with no ThemeTokenSpec (add one so the " +
                "theme editor and the colour identifier can see them): $missing",
            missing.isEmpty(),
        )
    }

    @Test fun `every spec points at a real field`() {
        val fields = colorFieldNames().toSet()
        val orphans = ThemeTokens.specs.map { it.key }.filter { it !in fields }
        assertTrue("Specs with no matching AppThemeColors field: $orphans", orphans.isEmpty())
    }

    @Test fun `spec keys and labels are unique`() {
        val keys = ThemeTokens.specs.map { it.key }
        assertEquals("Duplicate token keys", keys.size, keys.distinct().size)
        val labels = ThemeTokens.specs.map { it.label }
        assertEquals("Duplicate token labels", labels.size, labels.distinct().size)
    }

    @Test fun `the forecast panel's series are separately themeable`() {
        // The point of the tokens: four series that used to share two.
        val keys = ThemeTokens.specs.map { it.key }
        listOf("weatherTemp", "weatherHumidity", "weatherPrecip", "weatherWind")
            .forEach { assertTrue("$it missing from the registry", it in keys) }
    }
}
