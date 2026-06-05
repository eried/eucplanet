package com.eried.eucplanet.ble

/**
 * Stream-cipher helper for the Ninebot Z protocol. The wheel and phone agree
 * on a 16-byte session key during the GetKey handshake (param 0x00 to
 * KeyGenerator address 0x16), then XOR every subsequent on-wire frame against
 * that key with no IV, no rotation, and no authentication. See
 * docs/protocols/ninebot.md section 6.
 *
 * Protocol research credit: the WheelLog community (
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
class NinebotZCrypto {

    /**
     * 16-byte gamma keystream. Held as a var so the adapter can swap it in
     * after the GetKey reply parses; null means "no key yet, frames are
     * plaintext on the wire": only the initial GetKey request and its
     * reply travel that way.
     */
    @Volatile private var gamma: ByteArray? = null

    /** True once a key has been installed and frames need XOR'ing. */
    val hasKey: Boolean get() = gamma != null

    /**
     * Install the 16-byte session key extracted from the GetKey reply data.
     * Caller must pass exactly 16 bytes; longer or shorter is rejected so a
     * malformed frame can't poison the keystream silently.
     */
    fun setKey(key: ByteArray) {
        require(key.size == KEY_LENGTH) {
            "Ninebot Z key must be $KEY_LENGTH bytes, got ${key.size}"
        }
        gamma = key.copyOf()
    }

    /** Drop the key; called from the adapter on disconnect. */
    fun clearKey() {
        gamma = null
    }

    /**
     * Apply the XOR keystream in place. The two magic bytes (0x5A 0xA5) and
     * the length byte at offset 2 stay plaintext on the wire; everything
     * else from offset 3 to the end of the frame is XOR'd against
     * `gamma[(j-1) mod 16]` where `j` is the offset within the encrypted
     * region (so on-wire offset 3 picks gamma[0], offset 4 picks gamma[1],
     * and so on). The cipher is involutive: same call decrypts.
     *
     * Returns true when a key was installed and the frame was transformed,
     * false when no key was set (caller must treat the buffer as plaintext).
     */
    fun applyInPlace(frame: ByteArray): Boolean {
        val key = gamma ?: return false
        if (frame.size <= 3) return false
        // Spec section 6.2: index j runs 1..end of the encrypted region;
        // gamma index is (j - 1) mod 16. Translating to on-wire offsets,
        // on-wire offset i (for i >= 3) uses gamma[(i - 3) mod 16].
        for (i in 3 until frame.size) {
            frame[i] = (frame[i].toInt() xor (key[(i - 3) % KEY_LENGTH].toInt() and 0xFF)).toByte()
        }
        return true
    }

    /**
     * Convenience non-mutating variant. Used by the parser for incoming
     * notifications where we want to keep the raw buffer for debug logs and
     * decode against a copy.
     */
    fun applyCopy(frame: ByteArray): ByteArray {
        val out = frame.copyOf()
        applyInPlace(out)
        return out
    }

    companion object {
        const val KEY_LENGTH = 16
    }
}
