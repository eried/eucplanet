package com.eried.eucplanet.util

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shared encoder against ZXing, the one Android draws with: the same
 * version, mask and every module, for each mode and error level, across
 * versions 1 to 40.
 */
class QrEncoderZxingTest {
    private val levels = listOf(
        QrEncoder.Ecc.L to ErrorCorrectionLevel.L,
        QrEncoder.Ecc.M to ErrorCorrectionLevel.M,
        QrEncoder.Ecc.Q to ErrorCorrectionLevel.Q,
        QrEncoder.Ecc.H to ErrorCorrectionLevel.H,
    )

    private fun assertSame(content: String) {
        for ((ours, theirs) in levels) {
            val code = runCatching {
                Encoder.encode(content, theirs, mapOf(EncodeHintType.MARGIN to 0))
            }.getOrNull() ?: continue
            val z = code.matrix
            val m = QrEncoder.encode(content, ours)
            assertEquals("version, $ours, ${content.take(30)}", code.version.versionNumber, (m.size - 17) / 4)
            assertEquals("mask, $ours, ${content.take(30)}", code.maskPattern, m.mask)
            assertEquals("size, $ours, ${content.take(30)}", z.width, m.size)
            for (y in 0 until z.height) for (x in 0 until z.width) {
                assertEquals("module $x,$y, $ours, ${content.take(30)}", z.get(x, y).toInt() == 1, m[x, y])
            }
        }
    }

    @Test fun urls() {
        listOf(
            "https://eucplanet.app", "https://example.com/a?b=c&d=e#frag",
            "https://maps.google.com/?q=59.9139,10.7522", "http://x.y",
        ).forEach(::assertSame)
    }

    @Test fun modes() {
        listOf("0", "12345678901234567", "HELLO WORLD $%*+-./:", "AC-42", "", "Hello").forEach(::assertSame)
    }

    @Test fun latin1AndBeyond() {
        listOf("Zürich café ñ", "Ålesund ø æ", "日本語テキスト", "emoji 😀 ok").forEach(::assertSame)
    }

    @Test fun everyVersion() {
        val r = kotlin.random.Random(7)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789/:.?=&"
        var len = 1
        while (len < 2900) {
            assertSame(String(CharArray(len) { alphabet[r.nextInt(alphabet.length)] }))
            assertSame(String(CharArray(len) { '0' + r.nextInt(10) }))
            assertSame(String(CharArray(len) { "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 "[r.nextInt(37)] }))
            len += 1 + len / 6
        }
    }
}
