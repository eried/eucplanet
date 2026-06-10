package com.eried.eucplanet.data

/**
 * Minimal file seam for exporting trip CSVs. iOS writes into the app's Documents
 * directory (visible in Files / shareable); Android keeps its own SAF/document
 * export in `:app` (shared no-op stub for now). Returns the written path or null.
 */
interface FileStore {
    fun writeText(name: String, content: String): String?
    fun list(): List<String>
}

/** Platform-provided file store. */
expect fun createFileStore(): FileStore
