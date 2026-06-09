package com.eried.eucplanet.util

import android.util.Log as AndroidLog

actual object Log {
    actual fun v(tag: String, msg: String) { AndroidLog.v(tag, msg) }
    actual fun d(tag: String, msg: String) { AndroidLog.d(tag, msg) }
    actual fun i(tag: String, msg: String) { AndroidLog.i(tag, msg) }
    actual fun w(tag: String, msg: String) { AndroidLog.w(tag, msg) }
    actual fun e(tag: String, msg: String) { AndroidLog.e(tag, msg) }
    actual fun e(tag: String, msg: String, throwable: Throwable?) { AndroidLog.e(tag, msg, throwable) }
}
