package com.eried.eucplanet.platform

import platform.Foundation.NSProcessInfo

actual fun availableProcessors(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()

actual fun runInParallel(tasks: List<() -> Unit>) {
    tasks.forEach { it() }
}
