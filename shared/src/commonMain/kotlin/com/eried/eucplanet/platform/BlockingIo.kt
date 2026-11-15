package com.eried.eucplanet.platform

/**
 * Runs blocking work off the caller's thread. Android interrupts it when the
 * caller is cancelled (`runInterruptible` on Dispatchers.IO, as the offline
 * router always ran); iOS has no thread interrupt, so it only waits there.
 */
expect suspend fun <T> runBlockingIo(block: () -> T): T
