package com.eried.eucplanet.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/** Android has every feature, so nothing on it is drawn as coming later. */
class PlatformFeaturesAndroidTest {
    @Test fun everythingIsAvailable() {
        assertTrue(missingPlatformFeatures.isEmpty())
        for (f in PlatformFeature.entries) assertTrue(f.available, f.name)
    }
}
