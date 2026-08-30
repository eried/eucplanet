package com.eried.eucplanet.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShareCryptoTest {
    private val raw = ByteArray(16) { (it + 1).toByte() }
    private val room = "AAAAAAAAAAAAAAAAAAAAAA"

    @Test fun deriveKey_is32Bytes_andDeterministic() {
        val k1 = ShareCrypto.deriveKey(raw); val k2 = ShareCrypto.deriveKey(raw)
        assertEquals(32, k1.size); assertArrayEquals(k1, k2)
    }
    @Test fun roundTrip() {
        val k = ShareCrypto.deriveKey(raw)
        val blob = ShareCrypto.encrypt(k, room, "hi".toByteArray())
        assertEquals(12 + 2 + 16, blob.size)                  // nonce + ct + GCM tag
        assertArrayEquals("hi".toByteArray(), ShareCrypto.decrypt(k, room, blob))
    }
    @Test fun wrongRoomAadFails() {
        val k = ShareCrypto.deriveKey(raw)
        val blob = ShareCrypto.encrypt(k, room, "hi".toByteArray())
        try { ShareCrypto.decrypt(k, "BBBBBBBBBBBBBBBBBBBBBB", blob); org.junit.Assert.fail("aad mismatch must fail") }
        catch (e: Exception) { /* expected */ }
    }
    @Test fun twoEncryptsDiffer_randomNonce() {
        val k = ShareCrypto.deriveKey(raw)
        assertNotEquals(ShareCrypto.b64u(ShareCrypto.encrypt(k, room, "x".toByteArray())),
                        ShareCrypto.b64u(ShareCrypto.encrypt(k, room, "x".toByteArray())))
    }
    @Test fun b64uRoundTrip_noPadding() {
        val s = ShareCrypto.b64u(raw)
        assertEquals(22, s.length); assertEquals(false, s.contains("="))
        assertArrayEquals(raw, ShareCrypto.unb64u(s))
    }

    /**
     * Pinned test vector for the web viewer (Task 7). rawKey = 0x01..0x10, roomId =
     * "AAAAAAAAAAAAAAAAAAAAAA", nonce = 12 zero bytes, plaintext = "hi". The JS
     * implementation must reproduce the same derived key and blob byte for byte.
     */
    @Test fun testVector_forWebViewer() {
        val zeroNonce = ByteArray(12)
        val k = ShareCrypto.deriveKey(raw)
        val blob = ShareCrypto.encryptWithNonce(k, room, zeroNonce, "hi".toByteArray())

        val keyHex = k.joinToString("") { "%02x".format(it) }
        val blobB64u = ShareCrypto.b64u(blob)
        println("testVector_forWebViewer derivedKeyHex=$keyHex")
        println("testVector_forWebViewer blobB64u=$blobB64u")

        // Pinned values, observed once from this implementation and locked in as a regression guard.
        assertEquals("127d30588b8b9efd214c54b706d030b524b76f2fb63617e1113f3571212bc582", keyHex)
        assertEquals("AAAAAAAAAAAAAAAA3XcJbTvCEWy-_Cezzo2ZLA6C", blobB64u)

        assertArrayEquals("hi".toByteArray(), ShareCrypto.decrypt(k, room, blob))
    }

    @Test
    fun hmacSha256_isStablePerInputAndDiffersAcrossRooms() {
        // The per-room sender id is HMAC(deviceSecret, roomId): the same phone
        // gets the same id every time it joins a room, and different rooms
        // cannot be linked to each other through it.
        val secret = ByteArray(16) { it.toByte() }
        val a1 = ShareCrypto.hmacSha256(secret, "room-A".toByteArray())
        val a2 = ShareCrypto.hmacSha256(secret, "room-A".toByteArray())
        val b = ShareCrypto.hmacSha256(secret, "room-B".toByteArray())
        assertArrayEquals(a1, a2)
        assertFalse(a1.contentEquals(b))
        assertEquals(32, a1.size)
        val other = ShareCrypto.hmacSha256(ByteArray(16) { (it + 1).toByte() }, "room-A".toByteArray())
        assertFalse(a1.contentEquals(other))
    }
}
