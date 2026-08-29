package com.eried.eucplanet.share

/** roomId is public (the relay sees it); key lives only in the URL fragment. */
data class ShareLink(val roomId: String, val key: ByteArray)

object ShareLinks {
    const val BASE = "https://eucplanet.ried.no/share"
    private val ID = Regex("^[A-Za-z0-9_-]{22}$")

    fun newLink(): ShareLink = ShareLink(ShareCrypto.b64u(ShareCrypto.randomBytes(16)), ShareCrypto.randomBytes(16))

    fun format(link: ShareLink): String = "$BASE#${link.roomId}.${ShareCrypto.b64u(link.key)}"

    fun parse(url: String): ShareLink? {
        if (!url.startsWith(BASE)) return null
        val frag = url.substringAfter('#', "")
        val parts = frag.split('.')
        if (parts.size != 2 || !ID.matches(parts[0]) || !ID.matches(parts[1])) return null
        return runCatching { ShareLink(parts[0], ShareCrypto.unb64u(parts[1])) }.getOrNull()
    }
}
