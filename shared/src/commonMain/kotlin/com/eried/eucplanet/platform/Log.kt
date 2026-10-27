package com.eried.eucplanet.platform

/**
 * Logging facade with the `android.util.Log` call shape, so shared code keeps
 * its `Log.d(TAG, "...")` lines and only swaps the import. Android logs through
 * `android.util.Log` exactly as before; iOS prints to the unified log.
 */
expect object Log {
    fun v(tag: String, msg: String): Int
    fun d(tag: String, msg: String): Int
    fun d(tag: String, msg: String, tr: Throwable?): Int
    fun i(tag: String, msg: String): Int
    fun i(tag: String, msg: String, tr: Throwable?): Int
    fun w(tag: String, msg: String): Int
    fun w(tag: String, msg: String, tr: Throwable?): Int
    fun w(tag: String, tr: Throwable?): Int
    fun e(tag: String, msg: String): Int
    fun e(tag: String, msg: String, tr: Throwable?): Int
}
