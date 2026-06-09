package com.eried.eucplanet.util

actual object Log {
    actual fun v(tag: String, msg: String) { println("V/$tag: $msg") }
    actual fun d(tag: String, msg: String) { println("D/$tag: $msg") }
    actual fun i(tag: String, msg: String) { println("I/$tag: $msg") }
    actual fun w(tag: String, msg: String) { println("W/$tag: $msg") }
    actual fun e(tag: String, msg: String) { println("E/$tag: $msg") }
    actual fun e(tag: String, msg: String, throwable: Throwable?) { println("E/$tag: $msg ${throwable ?: ""}") }
}
