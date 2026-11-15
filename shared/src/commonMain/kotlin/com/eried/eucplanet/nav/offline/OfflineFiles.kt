package com.eried.eucplanet.nav.offline

/**
 * The file calls the offline areas make, on paths: the tile store, the
 * downloads and the router's profiles. Android's is [JvmOfflineFiles]
 * (java.io, as the store always used), the iPhone's is IosOfflineFiles
 * (POSIX and NSFileManager).
 */
interface OfflineFiles {
    /** True for an existing regular file. */
    fun isFile(path: String): Boolean

    /** The size in bytes, 0 when missing. */
    fun length(path: String): Long

    /** Milliseconds since 1970, 0 when missing. */
    fun lastModified(path: String): Long

    fun setLastModified(path: String, ms: Long): Boolean

    /** The names of the regular files in [dir], empty when it cannot be read. */
    fun list(dir: String): List<String>

    /** Removes the file; false when there was none or it stayed. */
    fun delete(path: String): Boolean

    /** Renames [from] to [to] (which must not exist); false on failure. */
    fun rename(from: String, to: String): Boolean

    /** Makes the folder and the ones above it. */
    fun mkdirs(dir: String)

    /** The file as UTF-8 text, or null when it cannot be read. */
    fun readText(path: String): String?

    fun writeText(path: String, text: String)

    /** Writes [bytes] as the whole file. */
    fun writeBytes(path: String, bytes: ByteArray)

    /** Copies [from] over [to]. */
    fun copy(from: String, to: String)

    /** Opens [path] for writing, at its end when [append], else from empty. */
    fun openWrite(path: String, append: Boolean): FileSink

    /** Bytes free on the volume holding [dir], 0 when it cannot say. */
    fun freeBytes(dir: String): Long
}

/** A file being written; [close] when done. */
interface FileSink {
    fun write(buf: ByteArray, off: Int, len: Int)
    fun close()
}
