package com.eried.eucplanet.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

actual suspend fun <T> runBlockingIo(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }
