package com.eried.eucplanet.util

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun nowEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
