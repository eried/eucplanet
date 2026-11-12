package com.eried.eucplanet.platform

import com.eried.eucplanet.data.model.ActionCatalog
import com.eried.eucplanet.ui.settings.SettingsSearchIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The PlatformFeature registry points at things that exist, once each. */
class PlatformFeatureTest {

    @Test fun actionKeysAreCatalogActions() {
        for (f in PlatformFeature.entries) {
            val key = f.actionKey ?: continue
            assertTrue(ActionCatalog.byKey(key) != null, "${f.name}: $key")
        }
    }

    @Test fun sectionsAreSettingsSections() {
        // The search index is generated from every SectionDef, so its keys are the section keys.
        for (f in PlatformFeature.entries) {
            val key = f.settingsSection ?: continue
            assertTrue(key in SettingsSearchIndex.sections.keys, "${f.name}: $key")
        }
    }

    @Test fun keysAreUniqueAndFindTheirFeature() {
        val actions = PlatformFeature.entries.mapNotNull { it.actionKey }
        val sections = PlatformFeature.entries.mapNotNull { it.settingsSection }
        assertEquals(actions.size, actions.toSet().size)
        assertEquals(sections.size, sections.toSet().size)
        for (f in PlatformFeature.entries) {
            f.actionKey?.let { assertEquals(f, PlatformFeature.forAction(it)) }
            f.settingsSection?.let { assertEquals(f, PlatformFeature.forSection(it)) }
        }
        assertEquals(null, PlatformFeature.forAction("HORN"))
        assertEquals(null, PlatformFeature.forSection("general"))
    }
}
