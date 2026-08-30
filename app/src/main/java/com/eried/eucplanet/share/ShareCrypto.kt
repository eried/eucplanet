package com.eried.eucplanet.share

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end crypto for location share. Same contract as the web viewer:
 * key = HKDF-SHA256(rawKey, salt "eucshare-v1", info "aes-256-gcm", 32);
 * blob = nonce(12) || AES-256-GCM(plaintext, aad = roomId). The relay only
 * ever sees the blob, so it cannot read positions.
 */
object ShareCrypto {
    private const val SALT = "eucshare-v1"
    private const val INFO = "aes-256-gcm"
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun deriveKey(rawKey: ByteArray): ByteArray {
        // HKDF extract + expand (RFC 5869) with HMAC-SHA256, one block (32 bytes).
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(), "HmacSHA256"))
        val prk = mac.doFinal(rawKey)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(INFO.toByteArray()); mac.update(1.toByte())
        return mac.doFinal()
    }

    fun encrypt(key: ByteArray, roomId: String, plaintext: ByteArray): ByteArray =
        encryptWithNonce(key, roomId, randomBytes(12), plaintext)

    /** Fixed-nonce variant used only by the web-viewer test vector; production code must use [encrypt]. */
    internal fun encryptWithNonce(key: ByteArray, roomId: String, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(roomId.toByteArray())
        return nonce + c.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, roomId: String, blob: ByteArray): ByteArray {
        require(blob.size > 12) { "blob too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        c.updateAAD(roomId.toByteArray())
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }

    /** Plain HMAC-SHA256, for ids that must be stable per key and input. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun b64u(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
}
