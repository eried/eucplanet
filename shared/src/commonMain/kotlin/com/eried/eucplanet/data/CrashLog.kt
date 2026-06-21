package com.eried.eucplanet.data

/**
 * Crash-log store for the About → Crash logs tab (faithful to Android's
 * crash-file list). Crash files are plain text written by [installCrashHandler]
 * to the [FileStore] (the app's Documents dir on iOS), named `crash_<ms>.txt`.
 */
object CrashLog {
    const val PREFIX = "crash_"
    private var store: FileStore? = null

    /** Wire up the store + install the platform uncaught-exception hook. */
    fun init(fileStore: FileStore) {
        store = fileStore
        installCrashHandler()
    }

    fun list(): List<String> = store?.list()?.filter { it.startsWith(PREFIX) }?.sortedDescending() ?: emptyList()
    fun read(name: String): String = store?.readText(name) ?: ""
    fun delete(name: String) { store?.delete(name) }
    fun deleteAll() { list().forEach { store?.delete(it) } }
    fun write(name: String, content: String) { store?.writeText(name, content) }
}

/**
 * Installs the platform uncaught-exception hook that snapshots a crash file via
 * [CrashLog]. iOS uses Kotlin/Native's hook + UIDevice info; Android is a no-op
 * (the Android app keeps its own DiagnosticsLogger).
 */
expect fun installCrashHandler()
