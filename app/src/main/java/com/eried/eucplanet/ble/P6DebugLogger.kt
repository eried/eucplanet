package com.eried.eucplanet.ble

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-off diagnostic dumper enabled in the preview5 build so we can find
 * the right offsets for motor temperature and reverify the headlight
 * state byte on real hardware.
 *
 * Writes raw BLE notifications, writes, and a few NOTE markers to
 *   <getExternalFilesDir>/p6_debug.log
 * and mirrors the same lines to logcat under tag "P6DEBUG" so an `adb
 * logcat -s P6DEBUG` capture works equivalently.
 *
 * The file is capped at 4 MB; once full, further appends are dropped
 * (a single ride session generates well under that).
 *
 * Strip this whole class plus its call sites in the next preview once
 * the offsets are confirmed.
 */
object P6DebugLogger {
    private const val TAG = "P6DEBUG"
    private const val FILE_NAME = "p6_debug.log"
    private const val MAX_FILE_SIZE = 4L * 1024 * 1024

    @Volatile
    private var file: File? = null

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sessionStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: return
        val f = File(dir, FILE_NAME)
        try {
            if (f.exists() && f.length() > MAX_FILE_SIZE) f.delete()
            f.appendText("\n=== session start ${sessionStamp.format(Date())} ===\n")
        } catch (e: Exception) {
            Log.w(TAG, "init failed: ${e.message}")
            return
        }
        file = f
        Log.i(TAG, "Logging to ${f.absolutePath}")
    }

    fun rx(bytes: ByteArray) = append("RX ${bytes.size}  ${hex(bytes)}")
    fun tx(bytes: ByteArray) = append("TX ${bytes.size}  ${hex(bytes)}")
    fun note(msg: String) = append("NOTE  $msg")

    private fun append(line: String) {
        val stamped = "${ts.format(Date())} $line"
        Log.d(TAG, stamped)
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_SIZE) return
            f.appendText("$stamped\n")
        } catch (_: Exception) {
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }
}
