package com.eried.eucplanet

import androidx.compose.ui.window.ComposeUIViewController
import com.eried.eucplanet.ble.CompositeWheelAdapter
import com.eried.eucplanet.ble.IosWheelLink
import com.eried.eucplanet.di.SharedKoin
import com.eried.eucplanet.probe.ProbeScreen
import com.eried.eucplanet.probe.ProbeSession
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

/** Entry point the Swift host (iosApp/AppDelegate.swift) shows. */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val adapter = SharedKoin.koin.get<CompositeWheelAdapter>()
    val link = IosWheelLink(adapter)
    val session = ProbeSession(link, adapter)
    // For scripted runs on the simulator, which cannot tap:
    // SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:<id> xcrun simctl launch ...
    (NSProcessInfo.processInfo.environment["EUC_CONNECT"] as? String)?.let { link.connect(it, null, false) }
    return ComposeUIViewController { ProbeScreen(link, session) }
}
