package com.eried.eucplanet.platform

actual fun formatLocalDate(pattern: String, epochMs: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(epochMs))
