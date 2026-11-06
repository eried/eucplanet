package com.eried.eucplanet

import com.eried.eucplanet.audio.EngineProfile
import com.eried.eucplanet.ui.settings.SettingsSearchIndex
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every string shared code can ask Android for has an R.string id in the
 * generated table. A key missing there throws the moment it is read: the
 * settings screen once closed on open because the search index named texts
 * the table had never seen.
 */
class StringIdsTest {

    @Test fun `every text the settings search reads has an id`() {
        val keys = SettingsSearchIndex.sections.values.flatten()
            .flatMap { e -> listOfNotNull(e.res, e.under) + e.folds }
            .map { it.key }.toSet()
        val missing = keys.filter { StringIds.id(it) == 0 }.sorted()
        assertTrue("No R.string id for: $missing", missing.isEmpty())
    }

    @Test fun `every engine preset name has an id`() {
        val missing = EngineProfile.PROFILES.map { "engine_preset_" + it.key.lowercase() }
            .filter { StringIds.id(it) == 0 }
        // A preset without a translated name falls back to its displayName,
        // so only names the strings file has are expected here.
        val declared = java.io.File("src/main/res/values/strings.xml").readText()
        val expected = missing.filter { "name=\"$it\"" in declared }
        assertTrue("No R.string id for: $expected", expected.isEmpty())
    }
}
