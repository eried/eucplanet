package com.eried.eucplanet.platform

/** `Runtime.getRuntime().availableProcessors()`: the cores a CPU-bound loop can fan out to. */
expect fun availableProcessors(): Int

/**
 * Runs every task and returns once all are done. Android starts a thread per
 * task and joins them, as the GIF encoder always did; iOS runs them in turn.
 */
expect fun runInParallel(tasks: List<() -> Unit>)
