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
                PlatformFeature.GPS_SENSORS - PlatformFeature.HOME_WIDGETS,
            missingPlatformFeatures,
        )
        for (f in missingPlatformFeatures) assertFalse(f.available, f.name)
    }

    @Test fun theHomeScreenWidgetIsOnTheIphone() {
        // WidgetKit draws it from what IosWidgets writes, so its settings
        // open, with the iPhone's own words for adding one.
        assertTrue(PlatformFeature.HOME_WIDGETS.available)
        assertFalse(com.eried.eucplanet.ui.settings.SettingsRow.HOME_WIDGET in IosSettingsHost.hiddenRows)
        assertEquals("widget_how_to_add_iphone", IosSettingsHost.widgetHowToAdd.key)
    }

    @Test fun navigationIsOnTheIphone() {
        // The route builder and the engine run on iOS (IosRouteBuilderHost,
        // IosNavigationHost), so the map button, its tile and settings open.
        assertTrue(PlatformFeature.NAVIGATION.available)
        assertFalse(PlatformFeature.NAVIGATION in missingPlatformFeatures)
    }

    @Test fun offlineAreasAreOnTheIphone() {
        // BRouter, the downloads and the offline map run on iOS (IosOffline,
        // IosTileJobs, IosOfflineMap), so the Navigation section shows the
        // offline routing block and the areas screen.
        assertFalse(com.eried.eucplanet.ui.settings.SettingsRow.OFFLINE_ROUTING in IosSettingsHost.hiddenRows)
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
        // So the tour's last page names the HUDs, Flic and the radar, not the watches.
        assertFalse(IosDashboardHost().supportsWatches)
        // A window over other apps, and sending the HUD its app.
        val rows = IosSettingsHost.hiddenRows
        assertTrue(com.eried.eucplanet.ui.settings.SettingsRow.PHONE_HUD_WINDOW in rows)
        assertTrue(com.eried.eucplanet.ui.settings.SettingsRow.HUD_APP_SEND in rows)
    }

    @Test fun theHudIsOnTheIphone() {
        // The HUD section is the shared one over the iPhone's own link
        // (IosHudLink), so its rows read the sink the link feeds.
        assertTrue(IosSettingsHost.hudAddressExample.startsWith("172.20.10."))
        assertTrue(com.eried.eucplanet.hud.protocol.HudDiscovery.isValidIpv4(IosSettingsHost.hudAddressExample))
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
