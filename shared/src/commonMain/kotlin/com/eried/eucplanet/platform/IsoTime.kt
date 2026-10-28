package com.eried.eucplanet.platform

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Epoch seconds of an ISO-8601 timestamp with an offset, such as Dropbox's
 * `2026-06-22T01:02:03Z`: what `OffsetDateTime.parse(s).toEpochSecond()` gave.
 * Throws on text that is not one.
 */
@OptIn(ExperimentalTime::class)
fun isoEpochSeconds(s: String): Long = Instant.parse(s).epochSeconds
