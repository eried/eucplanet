package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.TripDerive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A trip's name has to survive the round trip through a file.
 *
 * Renaming writes the name into the CSV's Extra column as `trip.name=`, which
 * is what lets it travel through export, a backup folder, Dropbox, and another
 * tool. Nothing ever read it back, though, so a trip that arrived by download
 * was inserted nameless and displayed as its date. Riders saw a rename they had
 * made days earlier come back as "the normal name" - and the name was sitting
 * in the file the whole time.
 */
class TripNameTravelsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()

    private fun csv(vararg rows: String): File {
        val f = tmp.newFile()
        f.writeText("date,speed,voltage,current,power,battery,latitude,longitude,mileage,extra\n" +
            rows.joinToString("\n") + "\n")
        return f
    }

    // --- what a rename actually puts in the file ---------------------------

    @Test fun `a rename lands in the Extra column`() {
        val src = csv("2026-02-01 10:00:00.000,10,80,1,80,90,52.1,4.3,100,")
        val dest = tmp.newFile()
        assertEquals(1, TripDerive.rewriteTripName(src, dest, "Coast road"))
        assertTrue(dest.readText().contains("trip.name=Coast road"))
    }

    @Test fun `a name with commas cannot break the file`() {
        val src = csv("2026-02-01 10:00:00.000,10,80,1,80,90,52.1,4.3,100,")
        val dest = tmp.newFile()
        TripDerive.rewriteTripName(src, dest, "Home, then  the \"pier\"")
        val header = dest.readText().lines().first().split(",").size
        dest.readText().lines().drop(1).filter { it.isNotBlank() }.forEach {
            assertEquals("the name broke the row into extra cells", header, it.split(",").size)
        }
    }

    @Test fun `renaming twice leaves one name, not two`() {
        val src = csv("2026-02-01 10:00:00.000,10,80,1,80,90,52.1,4.3,100,")
        val once = tmp.newFile()
        val twice = tmp.newFile()
        TripDerive.rewriteTripName(src, once, "First")
        TripDerive.rewriteTripName(once, twice, "Second")
        val text = twice.readText()
        assertEquals(1, text.split("trip.name=").size - 1)
        assertTrue(text.contains("trip.name=Second"))
    }

    // --- reading it back ---------------------------------------------------

    @Test fun `the sync parser looks for the name while it streams`() {
        assertTrue("CsvMeta carries no name", sync.contains("val name: String? = null"))
        assertTrue("the parser never finds the Extra column",
            sync.contains("""val extraIdx = header.indexOf("extra")"""))
        assertTrue("the parser does not read the name cell",
            sync.contains("""cell.startsWith("trip.name=", ignoreCase = true)"""))
    }

    @Test fun `every trip built from a file takes the file's name`() {
        // Three places create a trip from a CSV: the folder sync, the Dropbox
        // pull, and the worker's download. A rename that survives one route and
        // not another is the same bug wearing a different hat.
        assertEquals(3, sync.split("customName = meta.name,").size - 1)
    }

    @Test fun `a nameless file never wipes a name set on this phone`() {
        // An older copy, or one written by a tool that does not know the
        // convention, has no Extra cell. Taking its blank over the rider's name
        // would turn syncing into losing the rename.
        assertTrue(sync.contains("customName = meta.name ?: existing.customName"))
    }

    // --- keeping Dropbox's copy at the conflict prompt ---------------------

    @Test fun `choosing Dropbox's copy updates the trip, not just the file`() {
        // The bytes were always replaced. The row was only written when the
        // trip was new, so a conflict the rider resolved in Dropbox's favour
        // left the list showing what the old copy said - including its name.
        val download = sync.substringAfter("for (name in toDownload)").take(3400)
        assertTrue("the row is still only written for a new trip",
            download.contains("} else {"))
        assertTrue("the name does not follow the file",
            download.contains("customName = meta.name ?: existing.customName"))
        assertTrue("the dates and distance do not follow the file",
            download.contains("startTime = meta.startTime") &&
                download.contains("distanceKm = meta.distanceKm"))
    }

    @Test fun `the chosen copy is pushed on to the backup folder`() {
        // The folder still holds the copy the rider chose against.
        val download = sync.substringAfter("for (name in toDownload)").take(3400)
        assertTrue(download.contains("uploadStatus = if (settings.syncFolderUri != null) 1 else existing.uploadStatus"))
    }

    // --- resetting back to the date ----------------------------------------

    @Test fun `a blank name clears the cell from the file`() {
        // The dialog's Reset goes through the same door as a rename with an
        // empty field: the cell is emptied, so the cleared name travels to the
        // backups the same way a set name does.
        val src = csv("2026-02-01 10:00:00.000,10,80,1,80,90,52.1,4.3,100,")
        val named = tmp.newFile()
        val cleared = tmp.newFile()
        TripDerive.rewriteTripName(src, named, "Coast road")
        TripDerive.rewriteTripName(named, cleared, "")
        assertTrue("the name survived the reset", !cleared.readText().contains("trip.name="))
    }

    @Test fun `the dialog's reset clears and accepts in one tap`() {
        val dialog = File("src/main/java/com/eried/eucplanet/ui/recording/TripToolsDialog.kt").readText()
        val rename = dialog.substringAfter("fun RenameTripDialog").substringBefore("fun ")
        assertTrue("reset does not confirm the cleared name",
            rename.contains("""onConfirm("")"""))
        assertTrue("reset is offered on a trip with no name to clear",
            rename.contains("!currentName.isNullOrBlank()"))
    }

    // --- the wheel travels the same way ------------------------------------

    @Test fun `the sync parser reads the wheel identity too`() {
        // Same disease as the trip name, one column over: wheel.name= rows
        // were in every recorded CSV - eucviewer reads exactly them - and
        // every download dropped them, so the change-wheel picker on a
        // restored library offered nothing but "another wheel".
        assertTrue(sync.contains("val wheelJson: String? = null"))
        assertTrue(sync.contains("""cell.startsWith("wheel.name=", true)"""))
        assertTrue("downloads do not store the wheel",
            sync.contains("wheelMetaJson = meta.wheelJson,"))
        assertTrue("a wheel-less copy wipes a known wheel",
            sync.contains("wheelMetaJson = meta.wheelJson ?: existing.wheelMetaJson,"))
    }

    @Test fun `old rows are backfilled from their files, once`() {
        val repo = File("src/main/java/com/eried/eucplanet/data/repository/TripRepository.kt").readText()
        assertTrue("no backfill sweep at start", repo.contains("backfillWheelMetaFromFiles()"))
        // Files with no wheel rows are stamped "{}" - looked, nothing there -
        // so the sweep converges instead of re-reading every start.
        assertTrue(repo.contains("""tripDao.updateWheelMeta(trip.id, json ?: "{}")"""))
        val dao = File("src/main/java/com/eried/eucplanet/data/db/TripDao.kt").readText()
        assertTrue(dao.contains("WHERE wheelMetaJson IS NULL AND endTime IS NOT NULL"))
    }
}
