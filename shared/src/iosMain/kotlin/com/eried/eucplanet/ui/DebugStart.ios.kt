@file:OptIn(ExperimentalForeignApi::class)

package com.eried.eucplanet.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Reads the `EUC_DEMO_SCREEN` env var (set via `SIMCTL_CHILD_EUC_DEMO_SCREEN`
 *  on a `simctl launch`) so screenshots can target a specific screen. */
actual fun debugStartScreen(): String? = getenv("EUC_DEMO_SCREEN")?.toKString()
