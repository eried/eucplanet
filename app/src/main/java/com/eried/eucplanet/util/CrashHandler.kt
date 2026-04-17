package com.eried.eucplanet.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler {

    private const val MAX_FILES = 20

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeCrash(appContext, thread, throwable) } catch (_: Throwable) { /* swallow */ }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun crashesDir(context: Context): File {
        val dir = File(context.filesDir, "crashes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listCrashes(context: Context): List<File> =
        crashesDir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashesDir(context)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$ts.txt")

        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Throwable) { "?" }

        val header = buildString {
            appendLine("Time: ${Date()}")
            appendLine("App: ${context.packageName} $versionName")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("Thread: ${thread.name}")
            appendLine()
        }
        file.writeText(header + sw.toString())

        // Retention: keep newest MAX_FILES
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        if (all.size > MAX_FILES) {
            all.sortedByDescending { it.lastModified() }
                .drop(MAX_FILES)
                .forEach { runCatching { it.delete() } }
        }
    }
}
