package com.eried.eucplanet.data

import platform.Foundation.NSUserDefaults

/** iOS key-value store backed by NSUserDefaults (persists across launches). */
private class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }
}

actual fun createKeyValueStore(): KeyValueStore = UserDefaultsStore()
