package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.HeadlightReadback

/** Interprets an Aeon's complete, CRC-validated frame supplied by VeteranParser. */
internal object AeonHeadlightDecoder {
    fun decode(frame: ByteArray, receivedAtNanos: Long): HeadlightReadback? {
        // Page 8 is independently timed and must not replace a newer page-1 sample.
        if (frame.size != 87 || VeteranParser.pageId(frame) != 1) return null

        val level = when (frame[49].toInt() and 0xff) {
            0 -> HeadlightReadback.Level.OFF
            1 -> HeadlightReadback.Level.LOW
            2 -> HeadlightReadback.Level.MEDIUM
            3 -> HeadlightReadback.Level.HIGH
            else -> null
        }
        return HeadlightReadback(level, receivedAtNanos)
    }
}
