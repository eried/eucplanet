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
