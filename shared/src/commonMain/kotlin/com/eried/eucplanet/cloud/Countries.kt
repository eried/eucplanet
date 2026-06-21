package com.eried.eucplanet.cloud

/** ISO 3166-1 alpha-2 country with its display name. Flag emoji is derived from
 *  the code (regional-indicator letters) so we don't hardcode 250 emojis. */
data class Country(val code: String, val name: String) {
    val flag: String get() = flagEmoji(code)
}

object Countries {
    /** A broad list covering the EUC-active world; searchable in the picker. */
    val all: List<Country> = listOf(
        "AR" to "Argentina", "AU" to "Australia", "AT" to "Austria", "BE" to "Belgium",
        "BO" to "Bolivia", "BR" to "Brazil", "BG" to "Bulgaria", "CA" to "Canada",
        "CL" to "Chile", "CN" to "China", "CO" to "Colombia", "CR" to "Costa Rica",
        "HR" to "Croatia", "CY" to "Cyprus", "CZ" to "Czechia", "DK" to "Denmark",
        "DO" to "Dominican Republic", "EC" to "Ecuador", "EG" to "Egypt", "EE" to "Estonia",
        "FI" to "Finland", "FR" to "France", "GE" to "Georgia", "DE" to "Germany",
        "GR" to "Greece", "GT" to "Guatemala", "HK" to "Hong Kong", "HU" to "Hungary",
        "IS" to "Iceland", "IN" to "India", "ID" to "Indonesia", "IE" to "Ireland",
        "IL" to "Israel", "IT" to "Italy", "JP" to "Japan", "KZ" to "Kazakhstan",
        "KR" to "South Korea", "LV" to "Latvia", "LT" to "Lithuania", "LU" to "Luxembourg",
        "MY" to "Malaysia", "MT" to "Malta", "MX" to "Mexico", "MA" to "Morocco",
        "NL" to "Netherlands", "NZ" to "New Zealand", "NO" to "Norway", "PA" to "Panama",
        "PY" to "Paraguay", "PE" to "Peru", "PH" to "Philippines", "PL" to "Poland",
        "PT" to "Portugal", "PR" to "Puerto Rico", "RO" to "Romania", "RU" to "Russia",
        "SA" to "Saudi Arabia", "RS" to "Serbia", "SG" to "Singapore", "SK" to "Slovakia",
        "SI" to "Slovenia", "ZA" to "South Africa", "ES" to "Spain", "SE" to "Sweden",
        "CH" to "Switzerland", "TW" to "Taiwan", "TH" to "Thailand", "TR" to "Türkiye",
        "UA" to "Ukraine", "AE" to "United Arab Emirates", "GB" to "United Kingdom",
        "US" to "United States", "UY" to "Uruguay", "VE" to "Venezuela", "VN" to "Vietnam",
    ).map { (code, name) -> Country(code, name) }.sortedBy { it.name }

    fun nameFor(code: String): String? = all.firstOrNull { it.code.equals(code, ignoreCase = true) }?.name

    fun flagFor(code: String): String = flagEmoji(code)
}

/** Build the 🇺🇸-style flag emoji from a 2-letter code via regional indicators. */
fun flagEmoji(code: String): String {
    if (code.length != 2) return ""
    val a = code[0].uppercaseChar()
    val b = code[1].uppercaseChar()
    if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
    val base = 0x1F1E6 // 🇦
    return codePointToString(base + (a - 'A')) + codePointToString(base + (b - 'A'))
}

private fun codePointToString(cp: Int): String =
    if (cp <= 0xFFFF) {
        cp.toChar().toString()
    } else {
        val c = cp - 0x10000
        charArrayOf((0xD800 + (c shr 10)).toChar(), (0xDC00 + (c and 0x3FF)).toChar()).concatToString()
    }
