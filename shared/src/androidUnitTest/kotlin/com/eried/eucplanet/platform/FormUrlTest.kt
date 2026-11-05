package com.eried.eucplanet.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder

/** FormUrl encodes and decodes exactly as java.net does, so stored voice messages read back. */
class FormUrlTest {
    private val samples = listOf(
        "", "hello", "Hola, ¿qué tal?", "a b+c&d=e/f", "100% sure", "ÆØÅ æøå", "日本語のテキスト",
        "emoji 🚀 ok", ".-*_~!'()", "tab\\tand\\nnewline",
    )

    @Test fun `encodes as URLEncoder`() {
        for (s in samples) assertEquals(s, URLEncoder.encode(s, "UTF-8"), FormUrl.encode(s))
    }

    @Test fun `decodes as URLDecoder`() {
        for (s in samples) {
            val encoded = URLEncoder.encode(s, "UTF-8")
            assertEquals(s, URLDecoder.decode(encoded, "UTF-8"), FormUrl.decode(encoded))
            // Unescaped text, as an older file might hold.
            assertEquals(s, URLDecoder.decode(s.replace("%", ""), "UTF-8"), FormUrl.decode(s.replace("%", "")))
        }
    }
}
