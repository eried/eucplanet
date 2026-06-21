package com.eried.eucplanet.data

/**
 * Android keeps its existing trip export (Storage Access Framework / cloud sync)
 * in `:app`; shared in-memory stub so the module compiles for Android.
 */
private class MemoryFileStore : FileStore {
    private val files = LinkedHashMap<String, String>()
    override fun writeText(name: String, content: String): String? {
        files[name] = content
        return "(memory)/$name"
    }
    override fun list(): List<String> = files.keys.toList()
    override fun readText(name: String): String? = files[name]
    override fun delete(name: String): Boolean = files.remove(name) != null
}

actual fun createFileStore(): FileStore = MemoryFileStore()
