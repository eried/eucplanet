package com.eried.eucplanet.data

/**
 * Minimal persistent string key-value seam. iOS backs it with `NSUserDefaults`;
 * Android keeps its own DataStore in `:app` (shared in-memory stub for now). The
 * SettingsStore serializes [com.eried.eucplanet.data.model.AppSettings] through
 * this, so swapping in a richer backing later is a one-file change.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Platform-provided key-value store. */
expect fun createKeyValueStore(): KeyValueStore
