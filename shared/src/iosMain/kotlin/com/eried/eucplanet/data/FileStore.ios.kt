@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile

/** iOS file store: writes into the app's Documents directory (visible in Files). */
private class IosFileStore : FileStore {
    private fun docsDir(): String? =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String

    override fun writeText(name: String, content: String): String? {
        val dir = docsDir() ?: return null
        val path = "$dir/$name"
        val ok = (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
        return if (ok) path else null
    }

    override fun list(): List<String> {
        val dir = docsDir() ?: return emptyList()
        val items = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) ?: return emptyList()
        return items.filterIsInstance<String>()
    }

    override fun readText(name: String): String? {
        val dir = docsDir() ?: return null
        val data = NSFileManager.defaultManager.contentsAtPath("$dir/$name") ?: return null
        val len = data.length.toInt()
        if (len == 0) return ""
        val bytes = data.bytes?.readBytes(len) ?: return null
        return bytes.decodeToString()
    }

    override fun delete(name: String): Boolean {
        val dir = docsDir() ?: return false
        return NSFileManager.defaultManager.removeItemAtPath("$dir/$name", error = null)
    }
}

actual fun createFileStore(): FileStore = IosFileStore()
