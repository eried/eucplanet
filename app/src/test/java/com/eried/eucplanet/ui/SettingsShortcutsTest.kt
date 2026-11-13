package com.eried.eucplanet.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings deep-links land where they say, and backup conflicts surface
 * where the rider looks.
 *
 * Two field reports behind this. The charging monitor's Settings link stopped
 * landing on the Battery header when sections gained their open animation:
 * the scroll target was latched from its first position report, which became
 * a mid-slide reading. And the external-GPS menu item sent tab 7, which is
 * Integration - the GPS & sensors section had no tab number at all.
 */
class SettingsShortcutsTest {

    private val settings = File("../shared/src/commonMain/kotlin/com/eried/eucplanet/ui/settings/SettingsScreen.kt").readText()
    private val dash = File("../shared/src/commonMain/kotlin/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/TripUploadWorker.kt").readText()
    private val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()
    private val vm = File("../shared/src/commonMain/kotlin/com/eried/eucplanet/ui/dashboard/DashboardViewModel.kt").readText()

    // --- deep-link scroll --------------------------------------------------

    @Test fun `the scroll target is never latched from its first report`() {
        // Sections open through a 180 ms slide; the first reported position
        // is mid-animation. Every report must win, and the scroll waits for
        // the value to sit still.
        assertTrue("the mid-animation latch is back",
            !settings.contains("if (targetSectionTop == null) targetSectionTop"))
        val effect = settings.substringAfter("LaunchedEffect(scrollContainerTop, targetSectionTop")
            .substringBefore("hasScrolledToSection = true")
        assertTrue("the scroll no longer waits out the open animation",
            effect.contains("delay(240)"))
    }

    @Test fun `every tab number reaches the section its caller names`() {
        val map = settings.substringAfter("fun initialTabSectionKey").substringBefore("}")
        // The callers, and what they mean.
        assertTrue(map.contains("4 -> \"cloud\""))       // dashboard backup shortcut
        assertTrue(map.contains("8 -> \"navigator\""))   // navigation settings menu
        assertTrue(map.contains("9 -> \"general\""))     // charging monitor -> battery block
        assertTrue("GPS & sensors is unreachable by tab again", map.contains("10 -> \"location\""))
        // And the external-GPS menu item uses the new number, not Integration.
        val gpsMenu = dash.substringAfter("dash_external_gps_settings").take(400)
        assertTrue(gpsMenu.contains("onNavigateToSettings(10)"))
    }

    // --- backup conflicts --------------------------------------------------

    @Test fun `the tappable folder-gap message is gone from settings`() {
        assertTrue("the tap-to-copy line is back",
            !settings.contains("cloud_trips_folder_gap"))
    }

    @Test fun `the worker counts conflicts on every pass, and only conflicts`() {
        // A conflict is the one state the mirror must not repair alone: both
        // sides have the file, with different bytes. Missing files are the
        // sweep's job; matching files are done; size mismatch needs the rider.
        // One function does the counting, for the worker and the manual sync.
        assertTrue("the worker no longer counts on every pass",
            worker.substringBefore("if (pending.isEmpty()")
                .contains("syncManager.refreshFolderConflictCount(settings, folderSizes)"))
        val block = sync.substringAfter("suspend fun refreshFolderConflictCount").take(1300)
        assertTrue(block.contains("countFolderConflicts(localSizes, folderSizes)"))
        assertTrue("unfinished recordings counted as conflicts",
            block.contains("trip.endTime == null"))
        assertTrue("the count is not written back",
            block.contains("folderConflictCount = conflicts"))
    }

    @Test fun `the warning follows the folder and the library, not only the syncs`() {
        // Each of these left the warning up with nothing behind it.
        val unlink = sync.substringAfter("suspend fun clearSyncFolder").take(900)
        assertTrue("unlinking the folder leaves the warning up", unlink.contains("folderConflictCount = 0"))
        val link = sync.substringAfter("suspend fun setSyncFolder").take(700)
        assertTrue("a new folder keeps the old folder's count", link.contains("refreshFolderConflictCount("))
        val repo = File("src/main/java/com/eried/eucplanet/data/repository/TripRepository.kt").readText()
        assertTrue("deleting a trip leaves its conflict counted",
            repo.substringAfter("suspend fun deleteTrip(").take(900).contains("recountFolderConflictsIfWarned()"))
        assertTrue("clearing the library leaves the conflicts counted",
            repo.substringAfter("suspend fun clearAll()").take(900).contains("recountFolderConflictsIfWarned()"))
    }

    @Test fun `a manual sync counts again when it ends, however it ends`() {
        // The warning stayed up after the rider picked a side, until the
        // worker's next pass, 15 minutes or a ride later. It read as a bug.
        val body = sync.substringAfter("fun runSync() = withUploadPass {").take(700)
        assertTrue("the manual sync leaves the old count standing",
            body.contains("finally") && body.contains("refreshFolderConflictCount(settings)"))
        assertTrue("a cancelled sync skips the count",
            body.contains("NonCancellable"))
    }

    @Test fun `the conflicts register in Needs attention, with Fix going to Backups`() {
        // Not a one-off dashboard banner: the warning lives in the Needs
        // attention dialog with every other fixable problem, so the amber
        // triangle, the count badge and the Fix button all come for free.
        assertTrue("the standalone banner is back", !dash.contains("backup_conflict_warning"))
        val reg = vm.substringAfter("it.folderConflictCount").take(1500)
        // The text says where to go, by the labels as they read on screen.
        assertTrue("the warning no longer names where the Sync button is",
            reg.contains("Res.string.section_cloud_trips") && reg.contains("Res.string.cloud_retry_now"))
        assertTrue(reg.contains("appHealthRepository.upsert"))
        // Tab 13 is Backups at its Trips backup block, where the Sync button
        // is. Tab 4 opened the section top, with the button off screen below.
        assertTrue("Fix does not open Trips backup", reg.contains("settingsTab = 13"))
        val map = settings.substringAfter("fun initialTabSectionKey").substringBefore("}")
        assertTrue(map.contains("13 -> \"cloud\""))
        assertTrue("tab 13 lands on the section top, not the Trips backup block",
            settings.contains("val scrollToTripsBackup = initialTab == 13") &&
                settings.contains("onTripsBackupTop(it.positionInWindow().y)"))
        assertTrue("the warning never clears", reg.contains("dismiss(\"backup_conflicts\")"))
    }

    @Test fun `both conflict strings exist in every locale`() {
        val res = File("src/main/res")
        val missing = res.listFiles()!!
            .filter { it.isDirectory && it.name.startsWith("values") && File(it, "strings.xml").exists() }
            .filter {
                val t = File(it, "strings.xml").readText()
                !t.contains("backup_conflict_warning") || !t.contains("backup_conflict_title")
            }.map { it.name }
        assertTrue("locales missing conflict strings: $missing", missing.isEmpty())
    }

    @Test fun `the HUD app card and its resend question show both versions`() {
        val screen = File("src/main/java/com/eried/eucplanet/ui/settings/HudSettingsSections.kt").readText()
        val card = screen.substringAfter("private fun HudAppCard(").substringBefore("if (confirmRemove)")
        assertTrue("the phone's own version only shows for an older HUD",
            !card.contains("if (older) {"))
        val dialog = card.substringAfter("if (confirmResend)")
        assertTrue("the resend question does not show the two versions",
            dialog.contains("HudAppVersions("))
        // One column of names, one of versions, so the versions line up.
        val rows = screen.substringAfter("private fun HudAppVersions(").substringBefore("private fun HudAppCard(")
        assertTrue("the two versions are not in a column of their own",
            rows.contains("Res.string.hud_app_installed") && rows.contains("Res.string.hud_app_available") &&
                Regex("Column").findAll(rows).count() == 2)
    }

    @Test fun `with a HUD linked its app card slides in and the find-the-HUD setup folds away`() {
        val screen = File("src/main/java/com/eried/eucplanet/ui/settings/HudSettingsSections.kt").readText()
        val section = screen.substringAfter("private fun HudIntegrationSection(").substringBefore("Res.string.hud_server_enabled")
        assertTrue("the HUD app card pops in instead of sliding",
            Regex("""AnimatedVisibility\(visible = pairedHudVersion != null && hudTakesApp\) \{\s*HudAppCard\(viewModel\)""")
                .containsMatchIn(section))
        val setup = section.substringAfter("AnimatedVisibility(visible = pairedHudVersion == null)", "")
        assertTrue("discovery mode and the address stay on screen while a HUD is linked",
            setup.contains("Res.string.hud_discovery_mode") && setup.contains("Res.string.hud_ip_label"))
        // The switch that drops the link must never fold away with them.
        assertTrue("the link switch went into the folded part",
            screen.substringAfter("// end AnimatedVisibility for the find-the-HUD setup").take(900)
                .contains("Res.string.hud_server_enabled"))
    }

    @Test fun `the HUD app card says where the HUD was reached and how`() {
        val screen = File("src/main/java/com/eried/eucplanet/ui/settings/HudSettingsSections.kt").readText()
        val card = screen.substringAfter("private fun HudAppCard(").substringBefore("if (confirmResend)")
        assertTrue("the card no longer shows the HUD address",
            card.contains("hudServer.peer") && card.contains("connected = connected"))
        // Every way of finding it by itself reads Auto, the typed address Fixed.
        assertTrue("how the HUD was found is not told in the Discovery mode words",
            card.contains("Res.string.hud_discovery_auto") && card.contains("Res.string.hud_discovery_fixed"))
        val server = File("src/main/java/com/eried/eucplanet/service/hud/HudServer.kt").readText()
        assertTrue("the address shown is not the one the link uses",
            server.contains("set(value) { _peer.value = value }"))
    }
}
