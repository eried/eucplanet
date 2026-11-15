package com.eried.eucplanet.platform

/**
 * `SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))`: a
 * local date and time in the phone's language. iOS uses NSDateFormatter with
 * the same pattern letters.
 */
expect fun formatLocalDate(pattern: String, epochMs: Long): String

/**
 * `SimpleDateFormat(pattern, Locale.US).format(Date(epochMs))`: local time,
 * ASCII digits and English names whatever the phone's language, for files
 * other programs read (trip CSVs, file names).
 */
expect fun formatLocalDateUs(pattern: String, epochMs: Long): String

/**
 * A `SimpleDateFormat(pattern, Locale.US)` parser in local time (or in
 * [timeZoneId], an IANA name like "Europe/Berlin"), for reading
 * timestamps other programs wrote: strict unless [lenient] (SimpleDateFormat's
 * own default, which rolls a 61st second into the next minute). Not
 * thread-safe: one per caller.
 */
expect class LocalDateParserUs(pattern: String, lenient: Boolean = false, timeZoneId: String? = null) {
    /** Epoch-millis, or null when [text] does not match the pattern. */
    fun parse(text: String): Long?
}

/**
 * `DateFormat.getDateTimeInstance(MEDIUM, SHORT, Locale.getDefault())`: a
 * date and time as the phone's language writes them ("Nov 6, 2026, 14:05").
 */
expect fun formatMediumDateShortTime(epochMs: Long): String
