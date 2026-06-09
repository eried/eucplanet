package com.eried.eucplanet.util

/**
 * Wall-clock time in epoch milliseconds. Replaces `System.currentTimeMillis()`
 * in shared code (it is JVM-only). Android delegates to it; iOS uses NSDate.
 */
expect fun nowEpochMillis(): Long
