package com.eried.eucplanet.app

import com.eried.eucplanet.platform.Log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIWindow

/**
 * Debug builds only, for a field check from a Mac: EUC_SNAP="<delay s>,<count>,<ms>"
 * saves a burst of screenshots of the app's window to Documents/snaps, which
 * `devicectl device copy from` pulls. A phone's screen cannot be captured
 * from the Mac any other way.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosDebugSnaps {
    fun start() {
        val spec = NSProcessInfo.processInfo.environment["EUC_SNAP"] as? String ?: return
        val parts = spec.split(',').map { it.trim().toLongOrNull() }
        val wait = parts.getOrNull(0) ?: 15L
        val count = parts.getOrNull(1) ?: 20L
        val gap = parts.getOrNull(2) ?: 100L
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$docs/snaps"
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        MainScope().launch {
            delay(wait * 1000)
            for (i in 0 until count) {
                val window = UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow ?: break
                val image = UIGraphicsImageRenderer(window.bounds).imageWithActions { _ ->
                    window.drawViewHierarchyInRect(window.bounds, afterScreenUpdates = false)
                }
                UIImagePNGRepresentation(image)?.writeToFile("$dir/snap-${i.toString().padStart(3, '0')}.png", true)
                delay(gap)
            }
            Log.i("DebugSnaps", "saved $count snaps")
        }
    }
}
