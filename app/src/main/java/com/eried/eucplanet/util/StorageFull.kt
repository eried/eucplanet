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
