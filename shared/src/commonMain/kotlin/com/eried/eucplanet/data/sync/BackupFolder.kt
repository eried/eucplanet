package com.eried.eucplanet.data.sync

/** One entry in a [BackupFolder] listing. [lastModified] is in ms, 0 when the folder cannot tell. */
data class BackupFolderEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * The backup folder the rider picked, as the shared folder sync reads and
 * writes it: Android's document tree (SAF), the iPhone's folder from the
 * Files app. One folder can be shared by both phones, so every name and
 * subfolder in it is the one [BackupFolderSync] spells.
 *
 * Paths are relative to the folder, "/" between levels ("trips/archive"),
 * "" for the folder itself. Names match exactly; callers that must ignore
 * case (shared storage often does) list first and pick the spelling.
 *
 * Calls block: run them off the main thread.
 */
interface BackupFolder {
    /** Where the folder is, as Settings shows it; null when it cannot be told. */
    val displayName: String?

    /** The entries of [dir]; null when [dir] is missing, not a folder, or cannot be listed. */
    fun list(dir: String = ""): List<BackupFolderEntry>?

    /** The whole file; null when it is missing or cannot be read. */
    fun read(path: String): ByteArray?

    /** At most [max] bytes from the start of the file, in one read; null as [read]. */
    fun readHead(path: String, max: Int): ByteArray? =
        read(path)?.let { if (it.size > max) it.copyOf(max) else it }

    /**
     * Create [path] or replace a file of exactly that name in place. Its
     * folder must exist. A file this call created and could not fill is
     * removed again, so a failure never leaves an empty file behind.
     */
    fun write(path: String, bytes: ByteArray): Boolean

    /** Remove the file or folder at [path]; false when it is missing or the folder refuses. */
    fun delete(path: String): Boolean

    /** Make folder [path] and any folder above it that is missing; true when it exists afterwards. */
    fun ensureDirectory(path: String): Boolean

    /**
     * Move the file at [path] into the folder [toDir], keeping its name,
     * without copying where the storage can. False when it cannot; the
     * caller then copies.
     */
    fun moveInto(path: String, toDir: String): Boolean
}

/** Opens the folder a settings value (`AppSettings.syncFolderUri`) names. */
fun interface BackupFolderAccess {
    /** The folder, or null when it is no longer reachable (permission gone, bookmark dead, folder deleted). */
    fun open(uri: String): BackupFolder?
}

/** What a new file of [name] is, for storage that asks (Android's document providers). */
fun backupMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "json" -> "application/json"
    "csv" -> "text/csv"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}

/** [dir] and [name] joined into one folder path. */
internal fun folderPath(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"
