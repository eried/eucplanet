package com.eried.eucplanet.share

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/**
 * The shared cipher an iPhone in a group uses, against the vectors Android's
 * ShareCryptoTest pins for the web viewer, so all three read each other.
 */
class ShareCipherTest {
    private val raw = ByteArray(16) { (it + 1).toByte() }
    private val room = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun androidsWebViewerVector_keyAndBlob() {
        val k = ShareCipher.deriveKey(raw)
        assertEquals("127d30588b8b9efd214c54b706d030b524b76f2fb63617e1113f3571212bc582", hex(k))
        val blob = ShareCipher.encryptWithNonce(k, room, ByteArray(12), "hi".encodeToByteArray())
        assertEquals("AAAAAAAAAAAAAAAA3XcJbTvCEWy-_Cezzo2ZLA6C", ShareCipher.b64u(blob))
        assertContentEquals("hi".encodeToByteArray(), ShareCipher.decrypt(k, room, blob))
    }

    @Test
    fun androidsBlob_decrypts() {
        val k = ShareCipher.deriveKey(raw)
        val blob = ShareCipher.unb64u("AAAAAAAAAAAAAAAA3XcJbTvCEWy-_Cezzo2ZLA6C")
        assertEquals("hi", ShareCipher.decrypt(k, room, blob).decodeToString())
    }

    /** The GCM specification's AES-256 test cases 13 and 14 (no AAD). */
    @Test
    fun gcmSpecVectors() {
        val key = ByteArray(32)
        val empty = ShareCipher.encryptWithNonce(key, "", ByteArray(12), ByteArray(0))
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", hex(empty.copyOfRange(12, empty.size)))
        val one = ShareCipher.encryptWithNonce(key, "", ByteArray(12), ByteArray(16))
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18", hex(one.copyOfRange(12, 28)))
        assertEquals("d0d1c8a799996bf0265b98b5d48ab919", hex(one.copyOfRange(28, 44)))
    }

    @Test
    fun roundTrip_ofALongPayload_crossesBlocksAndAPartialLastBlock() {
        val k = ShareCipher.deriveKey(raw)
        val text = "{\"lat\":59.913868,\"lng\":10.752245,\"name\":\"Rider #1234\",\"t\":1731600000000}".encodeToByteArray()
        val blob = ShareCipher.encrypt(k, room, text)
        assertEquals(12 + text.size + 16, blob.size)
        assertContentEquals(text, ShareCipher.decrypt(k, room, blob))
    }

    @Test
    fun aWrongRoomOrATamperedBlob_fails() {
        val k = ShareCipher.deriveKey(raw)
        val blob = ShareCipher.encrypt(k, room, "hi".encodeToByteArray())
        assertFailsWith<Exception> { ShareCipher.decrypt(k, "BBBBBBBBBBBBBBBBBBBBBB", blob) }
        val tampered = blob.copyOf().also { it[13] = (it[13].toInt() xor 1).toByte() }
        assertFailsWith<Exception> { ShareCipher.decrypt(k, room, tampered) }
        assertFailsWith<Exception> { ShareCipher.decrypt(k, room, ByteArray(20)) }
    }

    @Test
    fun twoEncrypts_differ() {
        val k = ShareCipher.deriveKey(raw)
        assertNotEquals(
            ShareCipher.b64u(ShareCipher.encrypt(k, room, "x".encodeToByteArray())),
            ShareCipher.b64u(ShareCipher.encrypt(k, room, "x".encodeToByteArray())),
        )
    }

    /** RFC 4231 test case 2, and a key longer than the block (test case 6). */
    @Test
    fun hmacSha256_rfc4231() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hex(ShareCipher.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())),
        )
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            hex(
                ShareCipher.hmacSha256(
                    ByteArray(131) { 0xaa.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ),
            ),
        )
        val a = ShareCipher.hmacSha256(unhex("000102030405060708090a0b0c0d0e0f"), "room-A".encodeToByteArray())
        val b = ShareCipher.hmacSha256(unhex("000102030405060708090a0b0c0d0e0f"), "room-B".encodeToByteArray())
        assertFalse(a.contentEquals(b))
    }
}
