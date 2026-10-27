package com.eried.eucplanet.platform

/**
 * The JVM's single-byte charset rules, for platforms without them: a byte the
 * charset cannot map decodes to U+FFFD, a char it cannot encode (a surrogate
 * pair counts as one) encodes to '?'. Pinned against the JVM by CharsetCodecTest.
 */
object CharsetCodec {
    fun decodeAscii(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in offset until offset + length) {
            val b = bytes[i].toInt()
            sb.append(if (b >= 0) b.toChar() else '�')
        }
        return sb.toString()
    }

    fun decodeLatin1(bytes: ByteArray, offset: Int, length: Int): String {
        val sb = StringBuilder(length)
        for (i in offset until offset + length) sb.append((bytes[i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    fun encodeSingleByte(s: String, max: Int): ByteArray {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.add('?'.code.toByte()); i += 2; continue
            }
            out.add(if (c.code <= max) c.code.toByte() else '?'.code.toByte())
            i++
        }
        return out.toByteArray()
    }
}
