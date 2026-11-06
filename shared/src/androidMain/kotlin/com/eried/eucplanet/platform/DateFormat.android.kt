package com.eried.eucplanet.platform

actual fun formatLocalDate(pattern: String, epochMs: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(epochMs))

actual fun formatLocalDateUs(pattern: String, epochMs: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.US).format(java.util.Date(epochMs))

actual class LocalDateParserUs actual constructor(pattern: String) {
    private val fmt = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { isLenient = false }

    actual fun parse(text: String): Long? = try { fmt.parse(text)?.time } catch (_: Exception) { null }
}
