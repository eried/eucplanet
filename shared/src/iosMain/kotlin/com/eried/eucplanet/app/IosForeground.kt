package com.eried.eucplanet.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * Whether the app is in front, as Android's AppForeground: true from the
 * moment it comes forward until it goes to the background. Read on the main
 * thread first, so the value is right from the start.
 */
internal object IosForeground {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> by lazy {
        _visible.value = UIApplication.sharedApplication.applicationState !=
            UIApplicationState.UIApplicationStateBackground
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            _visible.value = false
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            _visible.value = true
        }
        _visible.asStateFlow()
    }
}
