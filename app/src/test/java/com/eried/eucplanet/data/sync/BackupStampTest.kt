package com.eried.eucplanet.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A settings backup says when it was written, in its first bytes. */
class BackupStampTest {

    private val at = 1_794_000_000_000L

    @Test fun `the stamp is the first key and reads back from the head`() {
        val body = "{\n  \"unitSpeed\": \"kmh\",\n  \"alarms\": []\n}"
        val stamped = BackupStamp.stamp(body, at)
        assertTrue(stamped.startsWith("{\n  \"backupAt\": $at,"))
        assertEquals(at, BackupStamp.read(stamped.take(BackupStamp.HEAD_BYTES)))
        // The settings after it are untouched.
        assertTrue(stamped.endsWith("\"unitSpeed\": \"kmh\",\n  \"alarms\": []\n}"))
    }

    @Test fun `a stamped file is still one valid object`() {
        val stamped = BackupStamp.stamp("{\"a\":1}", at)
        assertEquals("{\n  \"backupAt\": $at,\"a\":1}", stamped)
        assertEquals("{\n  \"backupAt\": $at\n}", BackupStamp.stamp("{}", at))
    }

    @Test fun `a backup from before the stamp has none`() {
        assertNull(BackupStamp.read("{\n  \"unitSpeed\": \"kmh\""))
        // The old stored field is a different key and is not mistaken for it.
        assertNull(BackupStamp.read("{\"lastSettingsBackupAt\": $at}"))
    }
}
