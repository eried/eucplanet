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
        return runCatching { ShareLink(parts[0], ShareCrypto.unb64u(parts[1])) }.getOrNull()
    }
}
