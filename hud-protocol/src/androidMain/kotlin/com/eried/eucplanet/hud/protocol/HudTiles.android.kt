package com.eried.eucplanet.hud.protocol

import java.security.MessageDigest

internal actual fun md5Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
