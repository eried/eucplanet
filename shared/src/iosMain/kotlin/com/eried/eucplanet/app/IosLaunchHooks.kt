package com.eried.eucplanet.app

import com.eried.eucplanet.ble.gps.VirtualExternalGps
import com.eried.eucplanet.data.model.ExternalGpsSource
import com.eried.eucplanet.data.model.HudDiscoveryMode
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import com.eried.eucplanet.hud.protocol.HudDiscovery
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.dashboard.DashboardDialogBus
import com.eried.eucplanet.ui.dashboard.sources.DataSourcesScript
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * Launch hooks for the external GPS, for scripted runs on the simulator,
 * which cannot be tapped (docs/IOS_SYNC.md):
 *
 * - SIMCTL_CHILD_EUC_SERVICE=1 turns Service Mode on, so the pairing card
 *   lists the virtual RaceBox.
 * - SIMCTL_CHILD_EUC_EXTGPS=VIRTUAL pairs the virtual RaceBox and connects
 *   it, as a tap on its Pair button does; =none forgets the pairing.
 * - SIMCTL_CHILD_EUC_GPS_SPEED=external shows the GPS speed on the
 *   dashboard and prefers the external box for it (both GPS and sensors
 *   switches on, saved like any settings change); =phone turns the second off.
 * - SIMCTL_CHILD_EUC_SOURCES=WHEEL:RACEBOX opens the dashboard's Data
 *   sources sheet on that tab or comparison; a third part calibrate=20 asks
 *   the calibration question 20 s later, apply=20 also presses Apply.
 * - SIMCTL_CHILD_EUC_OFFLINE=areas (with EUC_SCREEN=settings and
 *   EUC_SETTINGS_TAB=8) opens the Offline areas screen.
 * - SIMCTL_CHILD_EUC_NO_NETWORK=1 makes the app act as if it had no
 *   connection (IosNetwork.forceOffline): the simulator shares the Mac's
 *   network, so this is how the offline map and routing are seen there.
 * - SIMCTL_CHILD_EUC_HUD=127.0.0.1:28080 turns the HUD link on and dials that
 *   address (the manual address, saved like any settings change; the port may
 *   be left out); =auto turns it on and lets it search; =off turns it off;
 *   =browse changes nothing and dials nothing, it only names in the log the
 *   HUD Bonjour finds on this network.
 *   Android's HUD debug properties are hooks too: EUC_HUD_FORCE, EUC_HUD_PEER,
 *   EUC_DEMO, EUC_PROTO_MAJOR and EUC_PROTO_MINOR (IosHudPhone.debug).
 * - SIMCTL_CHILD_EUC_SHARE=scan (with EUC_SCREEN=route) opens the route
 *   builder on its scan window, as Share > Scan does.
 * - SIMCTL_CHILD_EUC_SCAN_CODE=<text> is what a scanner with no camera reads
 *   two seconds after it opens (the share scanner and the crew pass), as if
 *   it had been shown that code.
 */
internal object IosLaunchHooks {
    fun env(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

    val sources: DataSourcesScript? by lazy { env("EUC_SOURCES")?.let { DataSourcesScript.parse(it) } }

    private var offlineAreas = false

    /** True once, when EUC_OFFLINE=areas asked for the Offline areas screen. */
    fun takeOfflineAreas(): Boolean = offlineAreas.also { offlineAreas = false }

    private var shareScanner = false

    /** True once, when EUC_SHARE=scan asked for the route builder's scan window. */
    fun takeShareScanner(): Boolean = shareScanner.also { shareScanner = false }

    /** EUC_SCAN_CODE: the code a scanner with no camera reads. */
    val scanCode: String? by lazy { env("EUC_SCAN_CODE")?.takeIf { it.isNotBlank() } }

    fun start() {
        if (env("EUC_SERVICE") == "1") DiagnosticsLogger.enable()
        if (env("EUC_NO_NETWORK") == "1") IosNetwork.forceOffline()
        offlineAreas = env("EUC_OFFLINE")?.lowercase() == "areas"
        shareScanner = env("EUC_SHARE")?.lowercase() == "scan"
        when (env("EUC_EXTGPS")?.lowercase()) {
            "virtual" -> MainScope().launch {
                val gps = IosAppGraph.externalGps
                gps.setPairing(VirtualExternalGps.ADDRESS, VirtualExternalGps.NAME, ExternalGpsSource.RACEBOX)
                gps.connectPaired()
            }
            "none" -> MainScope().launch { IosAppGraph.externalGps.clearPairing() }
        }
        env("EUC_GPS_SPEED")?.lowercase()?.let { which ->
            kotlinx.coroutines.runBlocking {
                IosAppGraph.settings.update {
                    it.copy(gpsShowOnDashboard = true, gpsPrioritizeExternal = which == "external")
                }
            }
        }
        env("EUC_HUD")?.trim()?.let { hud(it) }
        if (sources != null) DashboardDialogBus.open("sources")
    }

    private fun hud(spec: String) {
        if (spec.lowercase() == "browse") {
            MainScope().launch {
                val found = IosHudMdns.resolve(timeoutMs = 8_000, serviceInfoTimeoutMs = 4_000)
                Log.i("HudServer", "[disc] Bonjour browse: ${found ?: "no HUD answered"}")
            }
            return
        }
        kotlinx.coroutines.runBlocking { hudSetting(spec) }
    }

    private suspend fun hudSetting(spec: String) {
        IosAppGraph.settings.update {
            when (spec.lowercase()) {
                "off" -> it.copy(hudServerEnabled = false)
                "auto" -> it.copy(hudServerEnabled = true, hudDiscoveryMode = HudDiscoveryMode.AUTO)
                else -> it.copy(
                    hudServerEnabled = true,
                    hudDiscoveryMode = HudDiscoveryMode.FIXED,
                    hudIp = spec.substringBefore(':'),
                    hudServerPort = spec.substringAfter(':', "").toIntOrNull() ?: HudDiscovery.DEFAULT_PORT,
                )
            }
        }
    }
}
