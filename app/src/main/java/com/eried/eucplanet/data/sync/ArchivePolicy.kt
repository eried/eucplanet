package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.repository.MoveOutcome

/**
 * What to do when archiving a trip half works.
 *
 * Archiving touches up to three places - Dropbox, the backup folder, and then
 * the phone - and any of them can fail. The rule is that a trip is either
 * archived everywhere it existed or nowhere: a half-archived trip is a ride the
 * rider cannot find in the app and cannot see in their backup either, and the
 * sync will argue with itself about it afterwards.
 *
 * Kept apart from the code that does the moving so the rule can be tested
 * without a network or a document provider, which are exactly the things that
 * make the failures hard to reproduce by hand.
 */
object ArchivePolicy {

    /**
     * @param archived the file is out of the way everywhere, so the phone's
     *   copy can go
     * @param rollback the Dropbox move has to be undone, because the backup
     *   folder would not take its copy
     */
    data class Decision(val archived: Boolean, val rollback: Boolean)

    /**
     * Decide from what the two backups did.
     *
     * @param dropbox what the Dropbox move returned, or null when Dropbox is
     *   not linked and there was nothing to move
     * @param folderOk whether the backup folder took its copy, which is also
     *   true when there is no folder or the file was never in it
     */
    fun decide(dropbox: MoveOutcome?, folderOk: Boolean): Decision = when {
        // Dropbox is attempted first precisely so that its failure costs
        // nothing: the folder has not been touched yet, so there is nothing to
        // undo and the trip stays exactly where it was.
        dropbox is MoveOutcome.Failed -> Decision(archived = false, rollback = false)
        folderOk -> Decision(archived = true, rollback = false)
        // The folder refused after Dropbox moved: put Dropbox back. Only when
        // it actually moved something - an absent file, or no Dropbox at all,
        // leaves nothing to reverse.
        else -> Decision(archived = false, rollback = dropbox is MoveOutcome.Moved)
    }

    /**
     * The same rule for a whole library at once.
     *
     * The Dropbox side is one batch, so it has already succeeded or failed for
     * everything by this point; the folder is per file, and whatever it refuses
     * goes back to where the batch found it.
     *
     * @param names every file the rider asked to archive
     * @param dropboxOk whether the batch move went through, true when Dropbox
     *   is not linked
     * @param folderAccepted the names the backup folder took
     * @return the names now archived, and the names whose Dropbox move must be
     *   reversed
     */
    fun decideBatch(
        names: List<String>,
        dropboxOk: Boolean,
        folderAccepted: Set<String>,
    ): Pair<Set<String>, List<String>> {
        if (!dropboxOk) return emptySet<String>() to emptyList()
        val archived = names.filterTo(HashSet()) { it in folderAccepted }
        return archived to names.filter { it !in archived }
    }
}
