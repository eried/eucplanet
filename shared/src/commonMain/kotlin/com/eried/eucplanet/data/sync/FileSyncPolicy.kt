package com.eried.eucplanet.data.sync

import com.eried.eucplanet.platform.ByteArrayOutputStream
import com.eried.eucplanet.platform.sha256

/** Result of reconciling one folder both ways. */
data class FileSyncPlan(
    val upload: List<String>,    // on phone only -> send up (phone's spelling)
    val download: List<String>,  // on Dropbox only -> pull down (Dropbox's spelling)
    val conflicts: List<String>, // on both, different content (phone's spelling)
)

/**
 * Pure per-file reconciliation for a folder that syncs both ways (themes,
 * overlays), mirroring the trip rule: a name on one side only transfers; a name
 * on both with a different fingerprint is a conflict; a matching one is already
 * synced. Names match whatever their case, as Dropbox and phone storage do.
 */
object FileSyncPolicy {
    fun <T> decide(local: Map<String, T>, remote: Map<String, T>): FileSyncPlan {
        val l = local.entries.associateBy { it.key.lowercase() }
        val r = remote.entries.associateBy { it.key.lowercase() }
        return FileSyncPlan(
            upload = (l.keys - r.keys).map { l.getValue(it).key }.sorted(),
            download = (r.keys - l.keys).map { r.getValue(it).key }.sorted(),
            conflicts = (l.keys intersect r.keys)
                .filter { l.getValue(it).value != r.getValue(it).value }
                .map { l.getValue(it).key }.sorted(),
        )
    }
}

/**
 * Dropbox's content_hash, computed locally: SHA-256 over the concatenated
 * SHA-256 of each 4 MiB block. Comparing it with the listing's own value tells
 * an edit that kept the byte count apart from an untouched file.
 */
object DropboxContentHash {
    private const val BLOCK = 4 * 1024 * 1024

    fun of(bytes: ByteArray): String {
        // The outer digest runs over the block digests laid end to end.
        val blocks = ByteArrayOutputStream()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + BLOCK, bytes.size)
            blocks.write(sha256(bytes.copyOfRange(i, end)))
            i = end
        }
        return sha256(blocks.toByteArray()).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}

/**
 * One local folder of `.json` files (themes/, overlays/) as [syncJsonFolder]
 * reads and writes it: the backup folder on Android, the app's Documents on iOS.
 */
interface JsonFolder {
    /** Every `.json` file and its bytes; null when the folder is out of reach. */
    suspend fun jsonFiles(): Map<String, ByteArray>?
    /** Write [name], replacing a file of that name in place. False on failure. */
    suspend fun write(name: String, bytes: ByteArray): Boolean
}

/**
 * Compare [local] with Dropbox's `/[sub]` by content. Only .json files take
 * part. Null when the folder or the listing is out of reach, which must not
 * read as "empty".
 */
suspend fun planJsonFolder(
    dropbox: com.eried.eucplanet.data.repository.DropboxRepository, sub: String, local: Map<String, ByteArray>,
): FileSyncPlan? {
    val remote = dropbox.listFolder("/$sub") ?: return null
    val localHashes = local.mapValues { (_, bytes) -> DropboxContentHash.of(bytes) }
    val remoteHashes = remote
        .filterKeys { it.endsWith(".json", ignoreCase = true) }
        .mapValues { it.value.contentHash ?: "size:${it.value.size}" }
    return FileSyncPolicy.decide(localHashes, remoteHashes)
}

/**
 * Sync [folder] both ways with Dropbox's `/[sub]`. Per file, like trips: on
 * one side only transfers; same name with different content is a conflict,
 * resolved by [choice] (APP keeps the phone's, FOLDER takes Dropbox's, the
 * rest skip). Returns the files transferred, or null when out of reach.
 */
suspend fun syncJsonFolder(
    dropbox: com.eried.eucplanet.data.repository.DropboxRepository,
    sub: String,
    folder: JsonFolder,
    choice: SyncChoice,
): Int? {
    val local = folder.jsonFiles() ?: return null
    val plan = planJsonFolder(dropbox, sub, local) ?: return null
    var count = 0
    suspend fun upload(name: String) {
        val bytes = local[name] ?: return
        if (dropbox.uploadFile("/$sub/$name", bytes)) count++
    }
    plan.upload.forEach { upload(it) }
    val toDownload = plan.download.toMutableList()
    when (choice) {
        SyncChoice.APP -> plan.conflicts.forEach { upload(it) }
        SyncChoice.FOLDER -> toDownload.addAll(plan.conflicts)
        else -> {}
    }
    for (name in toDownload) {
        val bytes = dropbox.downloadFile("/$sub/$name") ?: continue
        if (folder.write(name, bytes)) count++
    }
    return count
}
