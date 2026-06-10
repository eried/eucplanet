package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.ui.App
import platform.UIKit.UIViewController

/** iOS entry point: hosts the shared Compose [App] in a UIViewController that the
 *  SwiftUI `iosApp` embeds. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
