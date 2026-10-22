package com.eried.eucplanet.data.sync

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
        val outer = java.security.MessageDigest.getInstance("SHA-256")
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + BLOCK, bytes.size)
            outer.update(java.security.MessageDigest.getInstance("SHA-256").apply { update(bytes, i, end - i) }.digest())
            i = end
        }
        return outer.digest().joinToString("") { "%02x".format(it) }
    }
}
