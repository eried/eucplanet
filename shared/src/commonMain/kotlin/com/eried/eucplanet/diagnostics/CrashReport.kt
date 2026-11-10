package com.eried.eucplanet.diagnostics

import com.eried.eucplanet.platform.formatLocalDateUs

/**
 * The crash log both phones write when a Kotlin exception goes uncaught: the
 * same file names, the same "Key: value" header, a blank line, then the stack
 * trace, so the owner's tools read a crash from either phone. Android's
 * CrashHandler and the iPhone's IosCrashLog each fill the header with what
 * their platform knows.
 */
object CrashReport {
    /** The newest this many logs are kept in the app's crashes folder. */
    const val MAX_FILES = 20

    /** The copy a rider can reach without opening the app (Android's Downloads). */
    const val PUBLIC_PREFIX = "EUCPlanet-crashlog-"

    /** yyyyMMdd_HHmmss in the phone's time zone, the part every crash file name shares. */
    fun stamp(epochMs: Long): String = formatLocalDateUs("yyyyMMdd_HHmmss", epochMs)

    /** The log in the app's own crashes folder. */
    fun fileName(stamp: String): String = "crash_$stamp.txt"

    /** The public copy's name, so repeated crashes do not overwrite each other. */
    fun publicName(stamp: String): String = "$PUBLIC_PREFIX$stamp.txt"

    /** A file in the crashes folder that is a crash log. */
    fun isCrashLog(name: String): Boolean = name.endsWith(".txt")

    /** The header lines ("Time: ...", "App: ...") then a blank line, then the trace. */
    fun report(header: List<String>, trace: String): String = buildString {
        header.forEach { appendLine(it) }
        appendLine()
    } + trace

    /**
     * The logs past [MAX_FILES] to delete, oldest first out: [files] is each
     * log's name and last-modified time.
     */
    fun beyondRetention(files: List<Pair<String, Long>>): List<String> =
        files.filter { isCrashLog(it.first) }
            .sortedByDescending { it.second }
            .drop(MAX_FILES)
            .map { it.first }
}
