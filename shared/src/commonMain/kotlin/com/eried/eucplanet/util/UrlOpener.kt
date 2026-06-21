package com.eried.eucplanet.util

/** Opens an external URL in the system browser. iOS uses UIApplication; Android
 *  is a no-op in shared (the Android app opens URLs from its own UI). */
expect object UrlOpener {
    fun open(url: String)
}
