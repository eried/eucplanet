package com.eried.eucplanet.share

import com.eried.eucplanet.platform.secureRandomBytes
import com.eried.eucplanet.platform.sha256

/**
 * The live share's end-to-end crypto for a phone without javax.crypto (iOS),
 * byte for byte what Android's ShareCrypto and the web viewer do:
 * key = HKDF-SHA256(rawKey, salt "eucshare-v1", info "aes-256-gcm", 32);
 * blob = nonce(12) || AES-256-GCM(plaintext, aad = roomId), 128-bit tag.
 * The relay only ever sees the blob.
 *
 * AES and GCM are written out here (FIPS 197, SP 800-38D) over the platform's
 * SHA-256; Android keeps javax.crypto. ShareCipherTest pins the shared
 * vectors, and the app's ShareCipherParityTest checks it against javax.
 */
object ShareCipher {
    private val SALT = "eucshare-v1".encodeToByteArray()
    private val INFO = "aes-256-gcm".encodeToByteArray()
    private const val NONCE = 12
    private const val TAG = 16

    fun randomBytes(n: Int): ByteArray = secureRandomBytes(n)

    fun deriveKey(rawKey: ByteArray): ByteArray {
        // HKDF extract + expand (RFC 5869), one block (32 bytes).
        val prk = hmacSha256(SALT, rawKey)
        return hmacSha256(prk, INFO + byteArrayOf(1))
    }

    fun encrypt(key: ByteArray, roomId: String, plaintext: ByteArray): ByteArray =
        encryptWithNonce(key, roomId, randomBytes(NONCE), plaintext)

    /** Fixed-nonce variant, for the test vectors only; production code must use [encrypt]. */
    fun encryptWithNonce(key: ByteArray, roomId: String, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(nonce.size == NONCE) { "nonce must be 12 bytes" }
        val aes = Aes256(key)
        val j0 = nonce + byteArrayOf(0, 0, 0, 1)
        val ct = ctr(aes, j0, plaintext)
        return nonce + ct + tag(aes, j0, roomId.encodeToByteArray(), ct)
    }

    /** Throws when the blob is short or its tag does not match (a wrong key or room). */
    fun decrypt(key: ByteArray, roomId: String, blob: ByteArray): ByteArray {
        require(blob.size >= NONCE + TAG) { "blob too short" }
        val aes = Aes256(key)
        val j0 = blob.copyOfRange(0, NONCE) + byteArrayOf(0, 0, 0, 1)
        val ct = blob.copyOfRange(NONCE, blob.size - TAG)
        val expected = tag(aes, j0, roomId.encodeToByteArray(), ct)
        var diff = 0
        for (i in 0 until TAG) diff = diff or (expected[i].toInt() xor blob[blob.size - TAG + i].toInt())
        if (diff != 0) throw IllegalStateException("Tag mismatch")
        return ctr(aes, j0, ct)
    }

    /** Plain HMAC-SHA256 (RFC 2104), for ids stable per key and input. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val block = 64
        val k = (if (key.size > block) sha256(key) else key).copyOf(block)
        val inner = ByteArray(block) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(block) { (k[it].toInt() xor 0x5c).toByte() }
        return sha256(outer + sha256(inner + data))
    }

    fun b64u(bytes: ByteArray): String = ShareBase64.encode(bytes)
    fun unb64u(s: String): ByteArray = ShareBase64.decode(s)

    /** GCTR from inc32([j0]): the counter's last four bytes count up, wrapping. */
    private fun ctr(aes: Aes256, j0: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val counter = j0.copyOf()
        var off = 0
        while (off < input.size) {
            inc32(counter)
            val ks = aes.encryptBlock(counter)
            val n = minOf(16, input.size - off)
            for (i in 0 until n) out[off + i] = (input[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
        return out
    }

    private fun inc32(b: ByteArray) {
        for (i in 15 downTo 12) {
            b[i] = (b[i] + 1).toByte()
            if (b[i].toInt() != 0) return
        }
    }

    /** E(K, J0) xor GHASH(H, A, C). */
    private fun tag(aes: Aes256, j0: ByteArray, aad: ByteArray, ct: ByteArray): ByteArray {
        val h = aes.encryptBlock(ByteArray(16))
        val hHi = long(h, 0)
        val hLo = long(h, 8)
        var yHi = 0L
        var yLo = 0L
        fun absorb(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, off, minOf(off + 16, data.size))
                yHi = yHi xor long(block, 0)
                yLo = yLo xor long(block, 8)
                val (zHi, zLo) = gfMul(yHi, yLo, hHi, hLo)
                yHi = zHi
                yLo = zLo
                off += 16
            }
        }
        absorb(aad)
        absorb(ct)
        yHi = yHi xor (aad.size.toLong() * 8)
        yLo = yLo xor (ct.size.toLong() * 8)
        val (sHi, sLo) = gfMul(yHi, yLo, hHi, hLo)
        val e = aes.encryptBlock(j0)
        val s = ByteArray(16)
        putLong(s, 0, sHi)
        putLong(s, 8, sLo)
        return ByteArray(16) { (e[it].toInt() xor s[it].toInt()).toByte() }
    }

    /** X * Y in GCM's GF(2^128), bit 0 the most significant (SP 800-38D, 6.3). */
    private fun gfMul(xHi: Long, xLo: Long, yHi: Long, yLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var vHi = yHi
        var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit != 0L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val lsb = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (lsb != 0L) vHi = vHi xor (0xE1L shl 56)
        }
        return zHi to zLo
    }

    private fun long(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun putLong(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
    }
}

/** AES-256 encryption of one block (FIPS 197); GCM never needs the inverse cipher. */
private class Aes256(key: ByteArray) {
    private val roundKeys: IntArray

    init {
        require(key.size == 32) { "AES-256 needs a 32-byte key" }
        val w = IntArray(60)
        for (i in 0 until 8) {
            w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or (key[4 * i + 3].toInt() and 0xFF)
        }
        var rcon = 1
        for (i in 8 until 60) {
            var t = w[i - 1]
            if (i % 8 == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (i % 8 == 4) {
                t = subWord(t)
            }
            w[i] = w[i - 8] xor t
        }
        roundKeys = w
    }

    fun encryptBlock(input: ByteArray): ByteArray {
        val s = IntArray(16) { input[it].toInt() and 0xFF }
        addRoundKey(s, 0)
        for (round in 1 until 14) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, 14)
        return ByteArray(16) { s[it].toByte() }
    }

    // The state is column-major, as the block's bytes come: s[r + 4c].
    private fun addRoundKey(s: IntArray, round: Int) {
        for (c in 0 until 4) {
            val k = roundKeys[round * 4 + c]
            s[4 * c] = s[4 * c] xor (k ushr 24)
            s[4 * c + 1] = s[4 * c + 1] xor ((k ushr 16) and 0xFF)
            s[4 * c + 2] = s[4 * c + 2] xor ((k ushr 8) and 0xFF)
            s[4 * c + 3] = s[4 * c + 3] xor (k and 0xFF)
        }
    }

    private fun subBytes(s: IntArray) {
        for (i in 0 until 16) s[i] = SBOX[s[i]]
    }

    private fun shiftRows(s: IntArray) {
        for (r in 1 until 4) {
            val row = IntArray(4) { c -> s[r + 4 * ((c + r) % 4)] }
            for (c in 0 until 4) s[r + 4 * c] = row[c]
        }
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c]
            val a1 = s[4 * c + 1]
            val a2 = s[4 * c + 2]
            val a3 = s[4 * c + 3]
            s[4 * c] = xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3
            s[4 * c + 1] = a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3
            s[4 * c + 2] = a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)
            s[4 * c + 3] = (xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)
        }
    }

    private fun subWord(w: Int): Int =
        (SBOX[w ushr 24] shl 24) or (SBOX[(w ushr 16) and 0xFF] shl 16) or
            (SBOX[(w ushr 8) and 0xFF] shl 8) or SBOX[w and 0xFF]

    companion object {
        private fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x1B else 0)) and 0xFF

        /** The S-box, built from its definition (the inverse in GF(2^8), then the affine map). */
        private val SBOX: IntArray = IntArray(256).also { box ->
            var p = 1
            var q = 1
            do {
                // p walks the multiplicative group by 3, q by its inverse (1/3).
                p = p xor xtime(p)
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xFF
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4)
                box[p] = (x xor 0x63) and 0xFF
            } while (p != 1)
            box[0] = 0x63
        }

        private fun rotl(x: Int, n: Int): Int = ((x shl n) or (x ushr (8 - n))) and 0xFF
    }
}
