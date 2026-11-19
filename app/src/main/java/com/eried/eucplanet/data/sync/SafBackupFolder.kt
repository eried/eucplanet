package com.eried.eucplanet.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * The backup folder the rider picked with ACTION_OPEN_DOCUMENT_TREE, for the
 * shared [BackupFolderSync]. Every call goes to the document provider
 * through DocumentsContract, as DocumentFile does underneath: a listing is
 * one query for every child's name, type, size and date, and is kept for the
 * life of this object, so reading the files it listed does not walk the
 * folder again. A change made through this object drops the listing of the
 * folder it changed.
 */
class SafBackupFolder private constructor(
    private val context: Context,
    private val tree: DocumentFile,
) : BackupFolder {

    companion object {
        private const val TAG = "SyncManager"

        /** The folder [uri] names, or null if it is not reachable or not writable any more. */
        fun open(context: Context, uri: String): SafBackupFolder? {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canWrite() }
                ?: return null
            return SafBackupFolder(context, tree)
        }
    }

    private val resolver get() = context.contentResolver

    private class Child(val entry: BackupFolderEntry, val uri: Uri)

    /** Folder path -> its children by exact name, the first of a name kept as findFile would. */
    private val listings = HashMap<String, LinkedHashMap<String, Child>>()

    override val displayName: String?
        get() {
            // Prefer the decoded tree path so the user sees where the folder lives
            // (e.g. "Internal storage › Documents › EUC Planet") instead of just the leaf.
            val treePath = tree.uri.lastPathSegment
            if (!treePath.isNullOrEmpty()) {
                val parts = treePath.split(":", limit = 2)
                val volume = when (parts.getOrNull(0)) {
                    "primary" -> "Internal storage"
                    null, "" -> null
                    else -> parts[0]
                }
                val path = parts.getOrNull(1).orEmpty().trim('/')
                val segments = if (path.isEmpty()) emptyList() else path.split('/')
                val all = listOfNotNull(volume) + segments
                if (all.isNotEmpty()) return all.joinToString(" › ")
            }
            return tree.name
        }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")
    private fun nameOf(path: String) = path.substringAfterLast('/')

    /** The children of folder [dir], listed once. */
    private fun children(dir: String): LinkedHashMap<String, Child>? {
        listings[dir]?.let { return it }
        val dirUri = dirUri(dir) ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree.uri, DocumentsContract.getDocumentId(dirUri))
        val out = LinkedHashMap<String, Child>()
        try {
            resolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val entry = BackupFolderEntry(
                        name = name,
                        isDirectory = isDir,
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                    )
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree.uri, c.getString(0))
                    out.putIfAbsent(name, Child(entry, uri))
                }
            } ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Could not list /$dir in the backup folder", e)
            return null
        }
        listings[dir] = out
        return out
    }

    private fun dirUri(dir: String): Uri? {
        if (dir.isEmpty()) return tree.uri
        val child = children(parentOf(dir))?.get(nameOf(dir)) ?: return null
        return child.uri.takeIf { child.entry.isDirectory }
    }

    private fun childUri(path: String): Uri? = children(parentOf(path))?.get(nameOf(path))?.uri

    private fun changed(dir: String) {
        // The provider may have named a new document its own way, or a
        // folder below may have moved: list again next time.
        listings.keys.removeAll { it == dir || it.startsWith("$dir/") || dir.isEmpty() }
    }

    override fun list(dir: String): List<BackupFolderEntry>? = children(dir)?.values?.map { it.entry }

    override fun read(path: String): ByteArray? {
        val uri = childUri(path) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read /$path in the backup folder", e)
            null
        }
    }

    override fun readHead(path: String, max: Int): ByteArray? {
        val uri = childUri(path) ?: return null
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(max)
                val n = input.read(head)
                if (n > 0) head.copyOf(n) else ByteArray(0)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun write(path: String, bytes: ByteArray): Boolean {
        val dir = parentOf(path)
        val name = nameOf(path)
        val dirUri = dirUri(dir) ?: return false
        val existing = children(dir)?.get(name)?.takeIf { !it.entry.isDirectory }
        val target = existing?.uri
            ?: runCatching { DocumentsContract.createDocument(resolver, dirUri, backupMimeType(name), name) }.getOrNull()
            ?: return false
        // Replace an existing file in place ("wt" truncates, so a shorter
        // file leaves no stale bytes behind).
        val ok = try {
            resolver.openOutputStream(target, "wt")?.use { it.write(bytes); true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not write /$path in the backup folder", e)
            false
        }
        if (!ok && existing == null) runCatching { DocumentsContract.deleteDocument(resolver, target) }
        changed(dir)
        return ok
    }

    override fun delete(path: String): Boolean {
        val uri = childUri(path) ?: return false
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        changed(parentOf(path))
        return ok
    }

    override fun ensureDirectory(path: String): Boolean {
        if (path.isEmpty()) return true
        var dir = ""
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val parentUri = dirUri(dir) ?: return false
            val next = if (dir.isEmpty()) segment else "$dir/$segment"
            val existing = children(dir)?.get(segment)
            if (existing == null) {
                runCatching {
                    DocumentsContract.createDocument(
                        resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, segment)
                }.getOrNull() ?: return false
                changed(dir)
            } else if (!existing.entry.isDirectory) {
                return false
            }
            dir = next
        }
        return dirUri(path) != null
    }

    override fun moveInto(path: String, toDir: String): Boolean {
        val source = childUri(path) ?: return false
        val from = dirUri(parentOf(path)) ?: return false
        val to = dirUri(toDir) ?: return false
        val moved = runCatching { DocumentsContract.moveDocument(resolver, source, from, to) }.getOrNull()
        changed(parentOf(path))
        changed(toDir)
        return moved != null
    }
}
