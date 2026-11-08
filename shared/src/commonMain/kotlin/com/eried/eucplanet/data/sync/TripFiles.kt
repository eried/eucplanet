package com.eried.eucplanet.data.sync

/**
 * One file in the phone's trips folder, as the Dropbox sync sees it: a
 * `java.io.File` on Android, a path in Documents/trips on iOS.
 */
interface TripFile {
    val name: String
    fun exists(): Boolean
    /** Bytes on disk; 0 when the file is gone, like `File.length()`. */
    fun length(): Long
    /** When the phone last wrote it, in MILLISECONDS; 0 when it is gone. */
    fun lastModified(): Long
    fun readBytes(): ByteArray
    /** Stamp the file with [ms]; false when the system refuses, like `File.setLastModified`. */
    fun setLastModified(ms: Long): Boolean
    fun delete(): Boolean
    /** The file's lines, header first, streamed where the platform can; throws when it cannot be read. */
    fun <R> useLines(block: (Sequence<String>) -> R): R
}

/** The phone's trips folder. */
interface TripFileStore {
    /** Every file in the folder, of any type (Android's `listFiles { it.isFile }`). */
    fun files(): List<TripFile>
    /** The file called [name], whether or not it exists yet. */
    fun file(name: String): TripFile

    /**
     * Write [bytes] to [name] all at once. A trip cut off mid-write by the app
     * being killed carries a fresh timestamp, so the next pass took it for a
     * newer local edit and uploaded it over the good copy. Written beside it
     * first, then renamed, it is either the whole file or not there.
     * Throws when it cannot be written.
     */
    fun writeWhole(name: String, bytes: ByteArray): TripFile
}

/** The trip CSVs in [this], the only files that belong in Dropbox's /trips. */
fun TripFileStore.csvFiles(): List<TripFile> =
    files().filter { it.name.endsWith(".csv", ignoreCase = true) }
