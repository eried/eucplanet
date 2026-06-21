package com.eried.eucplanet.ui

/** Android opens to the Scan screen normally; no demo override. */
actual fun debugStartScreen(): String? = null

actual fun debugHudPeer(): String? = null

actual fun isSimulator(): Boolean = false
