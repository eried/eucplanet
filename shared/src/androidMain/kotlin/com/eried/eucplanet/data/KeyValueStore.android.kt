package com.eried.eucplanet.data

/**
 * Android keeps its persistent settings in the `:app` DataStore (which has the
 * Context); shared in-memory stub for now so the module compiles for Android.
 * The shared persistent store is used on iOS.
 */
private class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

actual fun createKeyValueStore(): KeyValueStore = MemoryStore()
