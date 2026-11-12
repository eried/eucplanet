package com.eried.eucplanet.app

import com.eried.eucplanet.platform.PlatformFeature
import com.eried.eucplanet.platform.available
import com.eried.eucplanet.platform.missingPlatformFeatures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The iPhone's split (docs/IOS_PARITY.md): what is coming shows muted, what
 * it will not have stays hidden, and no section is both.
 */
class IosPlatformFeaturesTest {

    @Test fun comingFeaturesAreMissingForNow() {
        assertEquals(PlatformFeature.entries.toSet(), missingPlatformFeatures)
        for (f in PlatformFeature.entries) assertFalse(f.available, f.name)
    }

    @Test fun aMutedSectionIsNotAlsoHidden() {
        for (f in missingPlatformFeatures) {
            val key = f.settingsSection ?: continue
            assertFalse(key in IosSettingsHost.hiddenSections, key)
        }
    }

    @Test fun neverFeaturesStayHidden() {
        assertTrue("watch" in IosSettingsHost.hiddenSections)
        assertFalse(IosSettingsHost.supportsVolumeKeys)
    }
}
