package com.eried.eucplanet.platform

/**
 * `java.net.URLEncoder.encode(s, "UTF-8")` and `URLDecoder.decode(s, "UTF-8")`
 * for shared code: form encoding, so a space is `+` and only letters, digits
 * and `.-*_` stay as they are. Strings written by one phone read back on the
 * other. [decode] throws IllegalArgumentException on a broken escape, as
 * URLDecoder does.
 */
object FormUrl {
    private const val KEEP = ".-*_"

    fun encode(s: String): String = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in KEEP -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append(v.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    fun decode(s: String): String {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '+' -> { out.add(' '.code.toByte()); i++ }
                '%' -> {
                    if (i + 2 >= s.length) throw IllegalArgumentException("Incomplete trailing escape (%) pattern")
                    val hex = s.substring(i + 1, i + 3)
                    val v = hex.toIntOrNull(16) ?: throw IllegalArgumentException("Illegal hex characters in escape (%) pattern")
                    out.add(v.toByte()); i += 3
                }
                else -> {
                    // A run of unescaped characters is taken as written.
                    var end = i
                    while (end < s.length && s[end] != '%' && s[end] != '+') end++
                    out.addAll(s.substring(i, end).encodeToByteArray().toList()); i = end
                }
            }
        }
        return out.toByteArray().decodeToString()
    }
}
