package com.eried.eucplanet.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * An iPhone and an Android phone in the same group: the shared cipher the
 * iPhone uses (ShareCipher) and Android's javax ShareCrypto must make and read
 * each other's blobs, for every payload length.
 */
class ShareCipherParityTest {
    private val rnd = Random(20261114)

    @Test
    fun keysAndSenderIds_match() {
        repeat(50) {
            val raw = rnd.nextBytes(16)
            assertArrayEquals(ShareCrypto.deriveKey(raw), ShareCipher.deriveKey(raw))
            val data = rnd.nextBytes(rnd.nextInt(0, 200))
            assertArrayEquals(ShareCrypto.hmacSha256(raw, data), ShareCipher.hmacSha256(raw, data))
        }
    }

    @Test
    fun sameNonce_sameBlob_atEveryLength() {
        val key = ShareCrypto.deriveKey(rnd.nextBytes(16))
        for (len in 0..100) {
            val nonce = rnd.nextBytes(12)
            val room = ShareCrypto.b64u(rnd.nextBytes(16))
            val plain = rnd.nextBytes(len)
            assertArrayEquals(
                "length $len",
                ShareCrypto.encryptWithNonce(key, room, nonce, plain),
                ShareCipher.encryptWithNonce(key, room, nonce, plain),
            )
        }
    }

    @Test
    fun eachReadsTheOthers() {
        repeat(30) {
            val key = ShareCrypto.deriveKey(rnd.nextBytes(16))
            val room = ShareCrypto.b64u(rnd.nextBytes(16))
            val plain = rnd.nextBytes(rnd.nextInt(1, 600))
            assertArrayEquals(plain, ShareCipher.decrypt(key, room, ShareCrypto.encrypt(key, room, plain)))
            assertArrayEquals(plain, ShareCrypto.decrypt(key, room, ShareCipher.encrypt(key, room, plain)))
        }
    }

    @Test
    fun base64_matches() {
        repeat(50) {
            val b = rnd.nextBytes(rnd.nextInt(0, 40))
            assertEquals(ShareCrypto.b64u(b), ShareCipher.b64u(b))
            assertArrayEquals(b, ShareCipher.unb64u(ShareCrypto.b64u(b)))
        }
    }
}
