package com.eried.eucplanet.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual object UrlOpener {
    actual fun open(url: String) {
        val nsurl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsurl)
    }
}
