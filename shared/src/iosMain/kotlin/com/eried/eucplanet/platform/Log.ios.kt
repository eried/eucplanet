package com.eried.eucplanet.platform

import platform.Foundation.NSLog

actual object Log {
    private fun out(level: String, tag: String, msg: String, tr: Throwable? = null): Int {
        val trace = tr?.let { "\n" + it.stackTraceToString() } ?: ""
        NSLog("%s", "$level/$tag: $msg$trace")
        return 0
    }

    actual fun v(tag: String, msg: String): Int = out("V", tag, msg)
    actual fun d(tag: String, msg: String): Int = out("D", tag, msg)
    actual fun d(tag: String, msg: String, tr: Throwable?): Int = out("D", tag, msg, tr)
    actual fun i(tag: String, msg: String): Int = out("I", tag, msg)
    actual fun i(tag: String, msg: String, tr: Throwable?): Int = out("I", tag, msg, tr)
    actual fun w(tag: String, msg: String): Int = out("W", tag, msg)
    actual fun w(tag: String, msg: String, tr: Throwable?): Int = out("W", tag, msg, tr)
    actual fun w(tag: String, tr: Throwable?): Int = out("W", tag, "", tr)
    actual fun e(tag: String, msg: String): Int = out("E", tag, msg)
    actual fun e(tag: String, msg: String, tr: Throwable?): Int = out("E", tag, msg, tr)
}
