package com.eried.eucplanet.data.repository

/** A file's lines, header first, opened afresh for every pass. */
interface LineSource {
    /** The lines as `BufferedReader.readLine` gives them: no trailing empty line for a final newline. */
    fun <R> useLines(block: (Sequence<String>) -> R): R
}

/** Where a rewritten trip file's lines go, one call per line. */
fun interface LineSink {
    fun write(line: String)
}

/**
 * The trips folder as the trip tools ([TripTools], [TripDerive]) see it: a
 * `java.io.File` folder on Android, Documents/trips on iOS. Names are bare
 * file names inside the folder.
 */
interface TripToolFiles {
    fun exists(name: String): Boolean

    /** Bytes on disk; 0 when the file is gone, like `File.length()`. */
    fun length(name: String): Long

    fun delete(name: String): Boolean

    /** Moves [from] to [to], like `File.renameTo`; false when it cannot. */
    fun rename(from: String, to: String): Boolean

    fun lines(name: String): LineSource

    /**
     * Writes [name] line by line through [block], replacing what was there.
     * The file is created at the first line, so a block that writes nothing
     * leaves no file behind. Throws when it cannot be written.
     */
    fun <R> write(name: String, block: (LineSink) -> R): R
}

/** [this] split into lines the way `BufferedReader.readLine` does it. */
fun String.readerLines(): Sequence<String> {
    if (isEmpty()) return emptySequence()
    val all = lineSequence()
    return if (endsWith('\n') || endsWith('\r')) {
        // lineSequence gives one empty line after a final newline; readLine does not.
        val n = all.count()
        all.take(n - 1)
    } else all
}
