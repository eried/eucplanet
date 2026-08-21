package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.MoveOutcome
import com.eried.eucplanet.data.sync.ArchivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Archiving when something goes wrong.
 *
 * The failures this guards against are the ones that are hardest to produce by
 * hand - a dropped connection halfway, a document provider refusing a write -
 * and the most expensive to get wrong, because the phone's copy is deleted on
 * the strength of this answer. The rule: a trip is archived everywhere or
 * nowhere, and the phone's copy only goes when the backups have let go.
 */
class ArchivePolicyTest {

    private val moved = MoveOutcome.Moved("/trips/archive/trip.csv")

    // --- the happy paths ---------------------------------------------------

    @Test fun `both sides took it, so the phone copy can go`() {
        val d = ArchivePolicy.decide(moved, folderOk = true)
        assertTrue(d.archived)
        assertFalse(d.rollback)
    }

    @Test fun `no Dropbox linked, folder took it`() {
        val d = ArchivePolicy.decide(null, folderOk = true)
        assertTrue(d.archived)
        assertFalse(d.rollback)
    }

    @Test fun `a trip that never reached Dropbox is not a failure`() {
        // Absent means the file was not there: the state archiving wanted.
        val d = ArchivePolicy.decide(MoveOutcome.Absent, folderOk = true)
        assertTrue(d.archived)
        assertFalse(d.rollback)
    }

    // --- the failures ------------------------------------------------------

    @Test fun `Dropbox failed, so nothing is archived and nothing to undo`() {
        val d = ArchivePolicy.decide(MoveOutcome.Failed, folderOk = false)
        assertFalse(d.archived)
        assertFalse(d.rollback)
    }

    @Test fun `Dropbox failing keeps the trip even if the folder would have taken it`() {
        // Ordering is the point: Dropbox is tried first so its failure costs
        // nothing. This pins that the folder result cannot rescue it.
        val d = ArchivePolicy.decide(MoveOutcome.Failed, folderOk = true)
        assertFalse(d.archived)
        assertFalse(d.rollback)
    }

    @Test fun `the folder refusing sends the Dropbox move back`() {
        val d = ArchivePolicy.decide(moved, folderOk = false)
        assertFalse(d.archived)
        assertTrue(d.rollback)
    }

    @Test fun `nothing was moved on Dropbox, so a folder refusal undoes nothing`() {
        assertFalse(ArchivePolicy.decide(MoveOutcome.Absent, folderOk = false).rollback)
        assertFalse(ArchivePolicy.decide(null, folderOk = false).rollback)
    }

    @Test fun `the phone copy is never dropped unless both backups let go`() {
        // The invariant, stated once over every combination.
        val dropboxCases = listOf(null, MoveOutcome.Absent, moved, MoveOutcome.Failed)
        for (dbx in dropboxCases) {
            for (folderOk in listOf(true, false)) {
                val d = ArchivePolicy.decide(dbx, folderOk)
                if (d.archived) {
                    assertTrue("archived while Dropbox failed", dbx !is MoveOutcome.Failed)
                    assertTrue("archived while the folder refused", folderOk)
                }
            }
        }
    }

    @Test fun `a rollback is only ever asked for after a real move`() {
        val dropboxCases = listOf(null, MoveOutcome.Absent, moved, MoveOutcome.Failed)
        for (dbx in dropboxCases) {
            for (folderOk in listOf(true, false)) {
                val d = ArchivePolicy.decide(dbx, folderOk)
                if (d.rollback) assertTrue(dbx is MoveOutcome.Moved)
            }
        }
    }

    // --- a whole library at once ------------------------------------------

    @Test fun `every file the folder took is archived`() {
        val names = listOf("a.csv", "b.csv", "c.csv")
        val (done, back) = ArchivePolicy.decideBatch(names, dropboxOk = true, folderAccepted = names.toSet())
        assertEquals(names.toSet(), done)
        assertTrue(back.isEmpty())
    }

    @Test fun `files the folder refused go back to where the batch found them`() {
        val names = listOf("a.csv", "b.csv", "c.csv")
        val (done, back) = ArchivePolicy.decideBatch(names, dropboxOk = true, folderAccepted = setOf("a.csv"))
        assertEquals(setOf("a.csv"), done)
        assertEquals(listOf("b.csv", "c.csv"), back)
    }

    @Test fun `a failed batch archives nothing at all`() {
        val names = listOf("a.csv", "b.csv")
        val (done, back) = ArchivePolicy.decideBatch(names, dropboxOk = false, folderAccepted = names.toSet())
        assertTrue(done.isEmpty())
        // Nothing moved, so there is nothing to put back either.
        assertTrue(back.isEmpty())
    }

    @Test fun `archiving nothing is not an error`() {
        val (done, back) = ArchivePolicy.decideBatch(emptyList(), dropboxOk = true, folderAccepted = emptySet())
        assertTrue(done.isEmpty())
        assertTrue(back.isEmpty())
    }

    @Test fun `archiving the same name twice keeps both, on both sides`() {
        // Clear all with archiving, restore the library from Dropbox, clear it
        // again: the same file names arrive at the archive a second time. An
        // archive that overwrote them would destroy the first generation, which
        // is the one thing archiving promises not to do.
        val sync = File("src/main/java/com/eried/eucplanet/data/sync/SyncManager.kt").readText()
        assertTrue("the folder archive does not look for a free name",
            sync.contains("freeArchiveName"))
        assertTrue("the archive name is not made unique",
            sync.contains("archive.findFile(candidate) == null"))
        val repo = File("src/main/java/com/eried/eucplanet/data/repository/DropboxRepository.kt").readText()
        val move = repo.substringAfter("suspend fun moveFile").take(600)
        assertTrue("the Dropbox move would overwrite", move.contains("autorename"))
    }

    @Test fun `the batch never claims a file the folder did not take`() {
        val names = (1..50).map { "trip_$it.csv" }
        val accepted = names.filterIndexed { i, _ -> i % 3 == 0 }.toSet()
        val (done, back) = ArchivePolicy.decideBatch(names, dropboxOk = true, folderAccepted = accepted)
        assertEquals(accepted, done)
        assertEquals(names.size - accepted.size, back.size)
        assertTrue(back.none { it in done })
    }
}
