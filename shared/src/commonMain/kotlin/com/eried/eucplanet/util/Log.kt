package com.eried.eucplanet.util

/**
 * Minimal multiplatform logger mirroring android.util.Log's surface so shared
 * code keeps its `Log.d(TAG, msg)` call sites unchanged. Android delegates to
 * android.util.Log; iOS prints to the console.
 */
expect object Log {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable?)
}
