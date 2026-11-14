package com.eried.eucplanet.share

import com.eried.eucplanet.platform.secureRandomBytes
import kotlin.io.encoding.Base64

/** roomId is public (the relay sees it); key lives only in the URL fragment. */
data class ShareLink(val roomId: String, val key: ByteArray)

object ShareLinks {
    const val BASE = "https://eucplanet.ried.no/share"
    private val ID = Regex("^[A-Za-z0-9_-]{22}$")

    fun newLink(): ShareLink = ShareLink(ShareBase64.encode(secureRandomBytes(16)), secureRandomBytes(16))

    fun format(link: ShareLink): String = "$BASE#${link.roomId}.${ShareBase64.encode(link.key)}"

    fun parse(url: String): ShareLink? {
        if (!url.startsWith(BASE)) return null
        // Two shapes: the shared link keeps the secret in the fragment
        // (BASE#room.key, never sent to a server); the web page's "Open in
        // EUC Planet" button hands the same secret over as a path segment
        // (BASE/room.key) inside an intent that Chrome resolves on the phone.
        val frag = when {
            '#' in url -> url.substringAfter('#')
            url.startsWith("$BASE/") -> url.removePrefix("$BASE/").substringBefore('?').trimEnd('/')
            else -> ""
        }
        val parts = frag.split('.')
        if (parts.size != 2 || !ID.matches(parts[0]) || !ID.matches(parts[1])) return null
        return runCatching { ShareLink(parts[0], ShareBase64.decode(parts[1])) }.getOrNull()
    }
}

/**
 * URL-safe base64 without padding, as the links carry it: what
 * `java.util.Base64.getUrlEncoder().withoutPadding()` writes, and read back
 * as `getUrlDecoder()` reads it: padding optional, and the spare bits of the
 * last character ignored rather than refused.
 */
object ShareBase64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

    /** Throws IllegalArgumentException on a character outside the alphabet or a lone last character. */
    fun decode(s: String): ByteArray {
        val text = s.trimEnd('=').also { t ->
            require(s.length - t.length <= 2) { "Input byte array has incorrect ending byte" }
        }
        val out = ArrayList<Byte>(text.length * 3 / 4)
        var bits = 0
        var n = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "Illegal base64 character ${c.code.toString(16)}" }
            bits = (bits shl 6) or v
            n += 6
            if (n >= 8) {
                n -= 8
                out.add((bits shr n).toByte())
                bits = bits and ((1 shl n) - 1)
            }
        }
        require(text.length % 4 != 1) { "Last unit does not have enough valid bits" }
        return out.toByteArray()
    }
}
