package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.di.KoinInitializer
import com.eried.eucplanet.ui.App
import platform.UIKit.UIViewController

/** iOS entry point: stands up the shared Koin DI graph (idempotent) then hosts
 *  the shared Compose [App] in a UIViewController that the UIKit `iosApp` embeds. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController {
    KoinInitializer.start()
    return ComposeUIViewController { App() }
}
