package com.eried.eucplanet.crews

import com.eried.eucplanet.BuildConfig

/**
 * A scanned crews pairing code, and the server it belongs to.
 *
 * The QR carries the host it was served from rather than relying on one compiled into the
 * app. That is deliberate and it is what lets a single build pair against a laptop while
 * somebody is working on the feature and against the real server in a rider's pocket — no
 * debug flavour, no second APK, no special rider id that routes elsewhere.
 *
 * It is also the obvious way to hand somebody else's app to somebody else's server, so the
 * host is only *adopted* under the rules in [trust].
 */
data class PairLink(
    /** Origin of the server, e.g. `https://eucstats.ried.no` — no trailing slash. */
    val host: String,
    /** The six-character code, upper case. */
    val code: String,
) {
    val apiBase: String get() = "$host/api/v1"

    /** True when this is the server the app normally talks to. */
    val isProduction: Boolean get() = host.equalsOrigin(productionOrigin())

    companion object {
        private const val CODE_MIN = 4
        private const val CODE_MAX = 12

        /**
         * Parse scanned text or a tapped link. Accepts the two shapes the server emits:
         *
         *   https://eucstats.ried.no/p/AB12CD        (what the QR encodes)
         *   eucplanet://pair?code=AB12CD&host=...    (the button on that landing page)
         *
         * Anything else — a wifi code, a product barcode, a URL from another site — is null,
         * and the scanner carries on looking.
         */
        fun parse(text: String?): PairLink? {
            val raw = text?.trim().orEmpty()
            if (raw.isEmpty() || raw.length > 400 || raw.any { it.isWhitespace() }) return null
            val scheme = raw.substringBefore("://", missingDelimiterValue = "").lowercase()
            val rest = raw.removePrefix("$scheme://")
            return when (scheme) {
                "eucplanet" -> parseAppScheme(rest)
                "http", "https" -> parseWebUrl(scheme, rest)
                else -> null
            }
        }

        /** `eucplanet://pair?code=AB12CD&host=https://example` */
        private fun parseAppScheme(rest: String): PairLink? {
            val path = rest.substringBefore('?')
            if (!path.trimEnd('/').equals("pair", ignoreCase = true)) return null
            val params = rest.substringAfter('?', "").split('&')
                .mapNotNull { kv ->
                    val k = kv.substringBefore('=', "")
                    if (k.isEmpty()) null else k.lowercase() to kv.substringAfter('=', "")
                }.toMap()
            val code = params["code"].cleanCode() ?: return null
            val host = params["host"]?.takeIf { it.isNotEmpty() }?.trimEnd('/')
                ?: return PairLink(productionOrigin(), code)
            if (!host.looksLikeOrigin()) return null
            return PairLink(host, code)
        }

        /** `https://host[:port]/p/AB12CD` — exactly that, nothing longer. */
        private fun parseWebUrl(scheme: String, rest: String): PairLink? {
            val authority = rest.substringBefore('/')
            val path = rest.removePrefix(authority).substringBefore('?').substringBefore('#')
            // userinfo is never part of a pairing link, and allowing it is how
            // https://eucstats.ried.no@attacker.example/p/CODE reads as the real host to a
            // person and as the attacker's host to a URL parser
            if (authority.isEmpty() || '@' in authority) return null
            if (!authority.all { it.isLetterOrDigit() || it in ".-:[]" }) return null
            val segs = path.split('/').filter { it.isNotEmpty() }
            if (segs.size != 2 || !segs[0].equals("p", ignoreCase = true)) return null
            val code = segs[1].cleanCode() ?: return null
            return PairLink("$scheme://$authority", code)
        }

        /** The origin half of the API base this build was compiled with. */
        fun productionOrigin(): String =
            BuildConfig.EUCSTATS_API_BASE_URL.substringBefore("/api/v1").trimEnd('/')

        private fun String?.cleanCode(): String? {
            val c = this?.trim()?.uppercase().orEmpty()
            if (c.length !in CODE_MIN..CODE_MAX) return null
            return if (c.all { it.isLetterOrDigit() }) c else null
        }

        private fun String.looksLikeOrigin(): Boolean =
            (startsWith("http://") || startsWith("https://")) && length < 200 &&
                none { it.isWhitespace() }

        private fun String.equalsOrigin(other: String): Boolean =
            trimEnd('/').equals(other.trimEnd('/'), ignoreCase = true)
    }
}

/** What the app is willing to do with a scanned link. */
enum class PairTrust {
    /** The server this build already talks to. Pair without comment. */
    PRODUCTION,

    /** Somewhere else, and developer mode is on. Pair, but say so loudly. */
    DEVELOPER,

    /** Somewhere else, and developer mode is off. Refuse. */
    REFUSED,
}

/**
 * Whether a scanned link may be used.
 *
 * A QR code is a thing anybody can print and tape to a wall, and approving a pairing sends
 * this rider's store_id to whatever server the code names. So a link pointing anywhere other
 * than the server this build already uses is refused outright unless the rider has turned
 * developer mode on themselves, in Settings, on this device. That one switch is the whole
 * difference between "testing against my laptop" and "a stranger's QR quietly harvesting
 * rider ids at a meet-up", and it costs nothing in the normal case because the normal case is
 * the production host.
 *
 * Note what is NOT a rule here: whether the host looks local. 10.0.2.2 and 192.168.x.x are
 * convenient for development, but "it is a private address" is not a safety property — the
 * person holding the QR code may well be on the same wifi.
 */
fun PairLink.trust(developerMode: Boolean): PairTrust = when {
    isProduction -> PairTrust.PRODUCTION
    developerMode -> PairTrust.DEVELOPER
    else -> PairTrust.REFUSED
}
