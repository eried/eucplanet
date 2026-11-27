package com.eried.eucplanet.util

import android.database.sqlite.SQLiteFullException
import android.system.ErrnoException
import android.system.OsConstants

/**
 * Whether this failure means the phone is out of storage.
 *
 * It matters to tell apart: a save that failed for lack of room is dropped
 * with a warning, and a database that would not open for lack of room is not
 * a broken database. File writes report it as an IOException whose message or
 * cause carries ENOSPC. SQLite reports it as SQLiteFullException, or, when it
 * cannot grow the shared-memory file it needs to open at all, as a disk I/O
 * error named SQLITE_IOERR_SHMSIZE.
 */
fun Throwable.isOutOfStorage(): Boolean {
    var t: Throwable? = this
    repeat(8) {
        val cur = t ?: return false
        if (cur is SQLiteFullException) return true
        if (cur is ErrnoException && cur.errno == OsConstants.ENOSPC) return true
        val message = cur.message.orEmpty()
        if (STORAGE_FULL_MARKS.any { message.contains(it) }) return true
        t = cur.cause
    }
    return false
}

private val STORAGE_FULL_MARKS = listOf("ENOSPC", "SQLITE_FULL", "SQLITE_IOERR_SHMSIZE")

/**
 * Whether a database that failed to open may be deleted and rebuilt.
 *
 * Only a file that is really unreadable, and only once a copy of it is safe.
 * A full phone fails the open too, and fails the copy as well, so without
 * both conditions a rider who opened the app with no storage left lost every
 * trip, alarm and wheel profile.
 */
fun mayRebuildDatabase(openError: Throwable, copyKept: Boolean): Boolean =
    copyKept && !openError.isOutOfStorage()

/**
 * Whether an uncaught failure may be answered with the storage warning in
 * place of closing the app.
 *
 * Saving an alarm on a full phone closed the app: the database refused the
 * write inside a background task, nothing caught it, and Android ended the
 * process. With no room at all the database cannot even be opened, so the app
 * then closed again on every start.
 *
 * Two things must hold. The cause is a phone out of storage. And the failure
 * was handed over by a background task (a coroutine), which leaves its thread
 * in working order: the task is over, the rest of the app is not. A failure
 * that is unwinding a thread, the main thread above all, cannot be survived
 * and closes the app as before. A coroutine marks what it hands over with a
 * note of its own; StorageFullSurvivalTest fails if a library update renames
 * that note, and the shrunk build keeps the name (proguard-rules.pro).
 */
fun survivesAsStorageFull(t: Throwable): Boolean =
    t.isOutOfStorage() && t.suppressed.any { it.javaClass.name == COROUTINE_REPORT_MARK }

private const val COROUTINE_REPORT_MARK = "kotlinx.coroutines.internal.DiagnosticCoroutineContextException"

/**
 * Set when the database could not be opened at start for lack of storage.
 * The main screen then shows the storage warning and nothing else, see
 * StorageFullStartScreen: every list would come up empty and read as lost
 * data, when it is all still there.
 */
object StorageStart {
    val dataUnavailable = kotlinx.coroutines.flow.MutableStateFlow(false)
}
