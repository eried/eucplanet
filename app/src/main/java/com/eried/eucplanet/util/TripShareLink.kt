package com.eried.eucplanet.util

/**
 * Short share links (`#d-…`) for the EUC Planet Trip Viewer
 * (https://eucviewer.ried.no/).
 *
 * The classic share link wraps a full Dropbox direct URL in `?file=<urlencoded>`,
 * which triples every separator through percent-encoding. Almost all of that is
 * boilerplate: only the Dropbox file id, the rlkey and the filename timestamp
 * carry information. The short form keeps exactly those:
 *
 *   classic: https://eucviewer.ried.no/?file=https%3A%2F%2Fdl.dropbox…%26dl%3D1
 *   short:   https://eucviewer.ried.no/#d-<fileId>-<ts36>-<rlkey>
 *
 * ~86 chars vs ~180, no percent-encoding (survives chat apps verbatim), ~half
 * the QR modules. The short form is an ADDITIVE optimisation for the one URL
 * shape our own share flow produces: encode ONLY when every rule passes, else
 * fall back to the classic `?file=` link. When in doubt, fall back.
 *
 * `d` is the format tag ("Dropbox template, v1"). `ts36` is unpadded lowercase
 * base36 of the 14 filename digits `YYYYMMDDHHMMSS` — a digit-string transform,
 * NOT a date (the filename is the exporting phone's wall-clock local time, so
 * this never touches epoch time or a timezone in either direction).
 */
object TripShareLink {

    const val VIEWER_BASE = "https://eucviewer.ried.no/"

    // /scl/fi/<fileId>/trip_<8 digits>_<6 digits>.csv
    private val PATH_RE = Regex("^/scl/fi/([a-z0-9]{1,64})/trip_(\\d{8})_(\\d{6})\\.csv$")
    private val RLKEY_RE = Regex("^[a-z0-9]{1,64}$")
    // d-<fileId>-<ts36>-<rlkey>
    private val TOKEN_RE = Regex("^d-([a-z0-9]{1,64})-([a-z0-9]{1,9})-([a-z0-9]{1,64})$")

    /**
     * The short viewer URL for [directUrl] when it is a shortenable Dropbox
     * direct link, else null (the caller should emit the classic `?file=` link).
     */
    fun shortViewerUrl(directUrl: String): String? {
        val token = encodeToken(directUrl) ?: return null
        return VIEWER_BASE + "#" + token
    }

    /** The `d-…` token for [directUrl], or null if it does not qualify. */
    fun encodeToken(directUrl: String): String? {
        val uri = try { java.net.URI(directUrl) } catch (_: Exception) { return null }
        // scheme https, host exactly dl.dropboxusercontent.com, no fragment.
        if (uri.scheme != "https") return null
        if (uri.host != "dl.dropboxusercontent.com") return null
        if (uri.rawFragment != null) return null

        val path = uri.rawPath ?: return null
        val pm = PATH_RE.matchEntire(path) ?: return null
        val fileId = pm.groupValues[1]
        val digits = pm.groupValues[2] + pm.groupValues[3] // YYYYMMDD + HHMMSS = 14

        // Query must contain EXACTLY two params: rlkey (valid) and dl=1. Any
        // extra parameter (e.g. the `st=` Dropbox adds to web-UI copies)
        // disqualifies the URL.
        val query = uri.rawQuery ?: return null
        var rlkey: String? = null
        var dlOk = false
        val parts = query.split("&")
        if (parts.size != 2) return null
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq < 0) return null
            val k = p.substring(0, eq)
            val v = p.substring(eq + 1)
            when (k) {
                "rlkey" -> { if (!RLKEY_RE.matches(v)) return null; rlkey = v }
                "dl" -> { if (v != "1") return null; dlOk = true }
                else -> return null
            }
        }
        if (rlkey == null || !dlOk) return null

        // ts36 = base36 of the 14-digit number (<= 10^14 < 2^53, exact as Long).
        val ts36 = digits.toLong().toString(36) // lowercase 0-9a-z, unpadded
        return "d-$fileId-$ts36-$rlkey"
    }

    /**
     * Rebuild the Dropbox direct URL from a `d-…` token (the leading `#` is
     * stripped if present), or null if the token is not a valid short link.
     * The app itself only encodes; this mirror exists for tooling and the
     * round-trip tests, and documents the exact inverse the viewer performs.
     */
    fun decodeToken(token: String): String? {
        val t = token.removePrefix("#")
        val m = TOKEN_RE.matchEntire(t) ?: return null
        val fileId = m.groupValues[1]
        val ts36 = m.groupValues[2]
        val rlkey = m.groupValues[3]
        val n = try { java.lang.Long.parseLong(ts36, 36) } catch (_: Exception) { return null }
        if (n < 0 || n > 9007199254740991L) return null // reject unless a safe integer
        val digits = n.toString()
        if (digits.length > 14) return null
        val padded = digits.padStart(14, '0')
        val ymd = padded.substring(0, 8)
        val hms = padded.substring(8, 14)
        return "https://dl.dropboxusercontent.com/scl/fi/$fileId/trip_${ymd}_$hms.csv?rlkey=$rlkey&dl=1"
    }
}
