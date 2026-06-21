@file:OptIn(ExperimentalNativeApi::class)

package com.eried.eucplanet.data

import com.eried.eucplanet.util.nowEpochMillis
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import platform.UIKit.UIDevice

/**
 * Kotlin/Native uncaught-exception hook: on an unhandled Kotlin exception, write
 * a crash snapshot (time, app, OS, device, stack trace) to the Documents dir
 * before the process terminates, so the About → Crash logs tab can show it next
 * launch. Wrapped in try/catch so the handler itself can never crash.
 */
actual fun installCrashHandler() {
    setUnhandledExceptionHook { throwable ->
        try {
            val ts = nowEpochMillis()
            val dev = UIDevice.currentDevice
            val content = buildString {
                appendLine("Time (epoch ms): $ts")
                appendLine("App: EUC Planet (iOS)")
                appendLine("OS: ${dev.systemName} ${dev.systemVersion}")
                appendLine("Device: ${dev.model}")
                appendLine()
                appendLine(throwable.stackTraceToString())
            }
            CrashLog.write("${CrashLog.PREFIX}$ts.txt", content)
            // Keep only the 20 newest, like Android.
            CrashLog.list().drop(20).forEach { CrashLog.delete(it) }
        } catch (_: Throwable) {
            // never let the crash handler crash
        }
    }
}
