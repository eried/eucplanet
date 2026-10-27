package com.eried.eucplanet.platform

import java.util.Calendar

actual fun localTimeParts(epochMs: Long): LocalTimeParts {
    val c = Calendar.getInstance().apply { timeInMillis = epochMs }
    return LocalTimeParts(
        year = c.get(Calendar.YEAR),
        month = c.get(Calendar.MONTH) + 1,
        day = c.get(Calendar.DAY_OF_MONTH),
        hour = c.get(Calendar.HOUR_OF_DAY),
        minute = c.get(Calendar.MINUTE),
        second = c.get(Calendar.SECOND),
        millis = c.get(Calendar.MILLISECOND),
    )
}
