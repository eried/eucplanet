package com.eried.eucplanet.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

actual suspend fun <T> runBlockingIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }
