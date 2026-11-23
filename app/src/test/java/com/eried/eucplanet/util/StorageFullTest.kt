package com.eried.eucplanet.util

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Opening the app with no storage left used to delete the database: the open
 * failed, the safety copy failed for the same reason, and the file was
 * deleted and rebuilt empty. These pin the two rules that stop it.
 */
class StorageFullTest {

    @Test
    fun aFullPhoneIsRecognisedFromEitherKindOfFailure() {
        assertTrue(SQLiteFullException().isOutOfStorage())
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isOutOfStorage())
        // What a cold start on a full phone really throws: SQLite cannot grow
        // the shared-memory file, and Android calls that a disk I/O error.
        assertTrue(
            RuntimeException("disk I/O error (code 4874 SQLITE_IOERR_SHMSIZE): , while compiling: PRAGMA journal_mode")
                .isOutOfStorage()
        )
        assertTrue(RuntimeException("database or disk is full (code 13 SQLITE_FULL)").isOutOfStorage())
        // Wrapped, the way Room and DataStore hand it over.
        assertTrue(RuntimeException("open failed", SQLiteFullException()).isOutOfStorage())
        assertTrue(IllegalStateException(IOException("write failed: ENOSPC (No space left on device)")).isOutOfStorage())
    }

    @Test
    fun otherFailuresAreNotAFullPhone() {
        assertFalse(IOException("Permission denied").isOutOfStorage())
        assertFalse(RuntimeException("disk I/O error (code 1802 SQLITE_IOERR_FSTAT)").isOutOfStorage())
        assertFalse(SQLiteDatabaseCorruptException().isOutOfStorage())
        assertFalse(IllegalStateException("A migration from 12 to 13 was required").isOutOfStorage())
    }

    @Test
    fun aFullPhoneNeverGetsTheDatabaseRebuilt() {
        assertFalse(mayRebuildDatabase(SQLiteFullException(), copyKept = true))
        assertFalse(mayRebuildDatabase(SQLiteFullException(), copyKept = false))
    }

    @Test
    fun aBrokenDatabaseIsRebuiltOnlyOnceItsCopyIsSafe() {
        val broken = SQLiteDatabaseCorruptException()
        assertTrue(mayRebuildDatabase(broken, copyKept = true))
        assertFalse(mayRebuildDatabase(broken, copyKept = false))
    }
}
