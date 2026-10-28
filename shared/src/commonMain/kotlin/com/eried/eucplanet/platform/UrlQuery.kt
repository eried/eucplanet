package com.eried.eucplanet.platform

/**
 * `Uri.parse(base).buildUpon().appendQueryParameter(..).build().toString()`
 * for shared code: each name and value is encoded as `Uri.encode` does, so
 * the URL comes out byte for byte as Android built it.
 */
class UrlQuery(private val base: String) {
    private val params = StringBuilder()

    fun appendQueryParameter(name: String, value: String): UrlQuery {
        params.append(if (params.isEmpty()) '?' else '&').append(encode(name)).append('=').append(encode(value))
        return this
    }

    fun build(): String = base + params

    companion object {
        private const val ALLOWED = "_-!.~'()*"

        /** `Uri.encode`: letters, digits and `_-!.~'()*` stay, the rest is UTF-8 %XX. */
        fun encode(s: String): String = buildString {
            for (b in s.encodeToByteArray()) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in ALLOWED) append(c)
                else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
