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
        assertEquals(
            PlatformFeature.entries.toSet() - PlatformFeature.NAVIGATION - PlatformFeature.CAMERA_STUDIO -
                PlatformFeature.GPS_SENSORS,
            missingPlatformFeatures,
        )
        for (f in missingPlatformFeatures) assertFalse(f.available, f.name)
    }

    @Test fun navigationIsOnTheIphone() {
        // The route builder and the engine run on iOS (IosRouteBuilderHost,
        // IosNavigationHost), so the map button, its tile and settings open.
        assertTrue(PlatformFeature.NAVIGATION.available)
        assertFalse(PlatformFeature.NAVIGATION in missingPlatformFeatures)
    }

    @Test fun theStudioIsOnTheIphone() {
        // The Overlay Studio runs on IosStudioHost, so the camera button, its
        // tile, the trips' Replay row and the tour's camera step open.
        assertTrue(PlatformFeature.CAMERA_STUDIO.available)
    }

    @Test fun gpsSensorsAreOnTheIphone() {
        // The external GPS box, the data sources sheet and the GPS and sensors
        // section run on the iPhone (IosExternalGpsLink, IosExternalGpsScan),
        // so the GPS icon's tap, its menu item, the section and the tour's GPS
        // step open, and the section is not hidden either.
        assertTrue(PlatformFeature.GPS_SENSORS.available)
        assertFalse(PlatformFeature.GPS_SENSORS in missingPlatformFeatures)
        assertFalse("location" in IosSettingsHost.hiddenSections)
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
        // iOS lets no app set the volume, so the volume actions are not offered.
        assertTrue(com.eried.eucplanet.ui.settings.SettingsRow.PHONE_VOLUME in IosSettingsHost.hiddenRows)
        // It can pause another app's music but not start it or skip tracks.
        assertFalse(IosSettingsHost.drivesMusicPlayer)
        // So the tour's last page names Flic and the radar, not the HUDs or watches.
        assertFalse(IosDashboardHost().supportsWatchesAndHud)
    }

    @Test fun radarAndTpmsAreOnTheIphone() {
        // Their sections draw the shared RadarSection and TpmsSection now, so
        // neither is listed as coming (SettingsSearchHiddenRowsTest checks the search).
        val names = missingPlatformFeatures.map { it.name }
        assertFalse("RADAR" in names)
        assertFalse("TPMS" in names)
    }

    @Test fun motorSoundIsOnTheIphone() {
        // The shared engine plays through IosEngineAudio, so its section opens.
        assertFalse("MOTOR_SOUND" in missingPlatformFeatures.map { it.name })
        assertFalse("motor" in IosSettingsHost.hiddenSections)
        assertTrue(com.eried.eucplanet.platform.PlatformFeature.forSection("motor") == null)
    }
}
