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

    private val settings = File("src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt").readText()
    private val dash = File("src/main/java/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt").readText()
    private val worker = File("src/main/java/com/eried/eucplanet/data/sync/TripUploadWorker.kt").readText()
    private val vm = File("src/main/java/com/eried/eucplanet/ui/dashboard/DashboardViewModel.kt").readText()

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
        val block = worker.substringAfter("val conflicts =").substringBefore("if (pending.isEmpty()")
        assertTrue(block.contains("local.length() != folderLen"))
        assertTrue("unfinished recordings counted as conflicts",
            block.contains("t.endTime == null"))
        assertTrue("the count is not written back",
            block.contains("folderConflictCount = conflicts"))
    }

    @Test fun `the conflicts register in Needs attention, with Fix going to Backups`() {
        // Not a one-off dashboard banner: the warning lives in the Needs
        // attention dialog with every other fixable problem, so the amber
        // triangle, the count badge and the Fix button all come for free.
        assertTrue("the standalone banner is back", !dash.contains("backup_conflict_warning"))
        val reg = vm.substringAfter("it.folderConflictCount").take(900)
        assertTrue(reg.contains("appHealthRepository.upsert"))
        assertTrue("Fix does not open the Backups section", reg.contains("settingsTab = 4"))
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
}
