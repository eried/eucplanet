package com.eried.eucplanet.platform

actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

actual fun runInParallel(tasks: List<() -> Unit>) {
    tasks.map { task -> Thread { task() }.apply { start() } }.forEach { it.join() }
}
