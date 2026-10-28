package com.eried.eucplanet.platform

/**
 * `SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))`: a
 * local date and time in the phone's language. iOS uses NSDateFormatter with
 * the same pattern letters.
 */
expect fun formatLocalDate(pattern: String, epochMs: Long): String
