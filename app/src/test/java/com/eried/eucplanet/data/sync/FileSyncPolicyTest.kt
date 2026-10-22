package com.eried.eucplanet.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

class FileSyncPolicyTest {
    @Test fun missingOnPhone_downloads() {
        val p = FileSyncPolicy.decide(local = emptyMap(), remote = mapOf("a.json" to 10L))
        assertEquals(listOf("a.json"), p.download)
        assertEquals(emptyList<String>(), p.upload); assertEquals(emptyList<String>(), p.conflicts)
    }
    @Test fun missingOnDropbox_uploads() {
        val p = FileSyncPolicy.decide(local = mapOf("b.json" to 5L), remote = emptyMap())
        assertEquals(listOf("b.json"), p.upload)
    }
    @Test fun sameNameSameFingerprint_isNoOp() {
        val p = FileSyncPolicy.decide(mapOf("c.json" to "h1"), mapOf("c.json" to "h1"))
        assertEquals(emptyList<String>(), p.upload + p.download + p.conflicts)
    }
    @Test fun sameNameDifferentFingerprint_conflicts() {
        val p = FileSyncPolicy.decide(mapOf("d.json" to "h1"), mapOf("d.json" to "h2"))
        assertEquals(listOf("d.json"), p.conflicts)
    }
    @Test fun namesDifferingOnlyInCase_areOneFile() {
        val same = FileSyncPolicy.decide(mapOf("Night.json" to "h"), mapOf("night.json" to "h"))
        assertEquals(emptyList<String>(), same.upload + same.download + same.conflicts)
        val edited = FileSyncPolicy.decide(mapOf("Night.json" to "a"), mapOf("night.json" to "b"))
        assertEquals("the phone's spelling is the one written to", listOf("Night.json"), edited.conflicts)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun contentHash_isTheHashOfBlockHashes() {
        val small = "{\"name\":\"Red\"}".toByteArray()
        assertEquals(hex(sha(sha(small))), DropboxContentHash.of(small))
        assertEquals("an empty file hashes no blocks", hex(sha(ByteArray(0))), DropboxContentHash.of(ByteArray(0)))
        val big = ByteArray(4 * 1024 * 1024 + 3) { (it % 251).toByte() }
        val expected = hex(sha(sha(big.copyOfRange(0, 4 * 1024 * 1024)) + sha(big.copyOfRange(4 * 1024 * 1024, big.size))))
        assertEquals(expected, DropboxContentHash.of(big))
    }

    @Test fun contentHash_seesASameSizeEdit() {
        val a = "{\"c\":\"#ff0000\"}".toByteArray(); val b = "{\"c\":\"#00ff00\"}".toByteArray()
        assertEquals(a.size, b.size)
        assert(DropboxContentHash.of(a) != DropboxContentHash.of(b))
    }
}
