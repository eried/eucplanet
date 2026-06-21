package com.eried.eucplanet.util

actual object UrlOpener {
    actual fun open(url: String) {} // Android app opens URLs from its own UI
}
