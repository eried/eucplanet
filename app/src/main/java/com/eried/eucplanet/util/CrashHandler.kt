package com.eried.eucplanet.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

        val dm = context.resources.displayMetrics
        val cfg = context.resources.configuration
        val locale = try {
            if (android.os.Build.VERSION.SDK_INT >= 24) cfg.locales[0].toLanguageTag()
            else @Suppress("DEPRECATION") cfg.locale.toLanguageTag()
        } catch (_: Throwable) { "?" }
        val rt = Runtime.getRuntime()
        val memMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)

        val header = buildString {
            appendLine("Time: ${Date()}")
            appendLine("App: ${context.packageName} $versionName")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("Display: ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi (x${dm.density})")
            appendLine("Locale: $locale")
            appendLine("Memory: ${memMb}MB used / ${maxMb}MB max")
            appendLine("Thread: ${thread.name}")
            appendLine()
        }
        val report = header + sw.toString()
        file.writeText(report)

        // Also drop a copy in public Downloads so a rider whose app crashes on
        // open - who can never reach the in-app logs to export it - can still
        // find the trace and send it to us. No permission needed on minSdk 29+
        // via MediaStore. Best-effort: a failure here must not break the save
        // above or re-enter the crash path.
        runCatching { writeToDownloads(context, "$DOWNLOADS_PREFIX$ts.txt", report) }

        // Retention: keep newest MAX_FILES
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return
        if (all.size > MAX_FILES) {
            all.sortedByDescending { it.lastModified() }
                .drop(MAX_FILES)
                .forEach { runCatching { it.delete() } }
        }
    }

    /** Crash logs land in Downloads as EUCPlanet-crashlog-<timestamp>.txt so
     *  repeated crashes don't overwrite each other. Nothing here is ever
     *  deleted - the rider owns their Downloads. */
    private const val DOWNLOADS_PREFIX = "EUCPlanet-crashlog-"

    /** Write [content] to public Downloads as [name] via MediaStore. No
     *  permission needed on Android 10+ (our minSdk), and no deletion. */
    private fun writeToDownloads(context: Context, name: String, content: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
