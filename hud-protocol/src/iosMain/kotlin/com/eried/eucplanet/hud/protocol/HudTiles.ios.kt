package com.eried.eucplanet.hud.protocol

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
internal actual fun md5Hex(bytes: ByteArray): String {
    val digest = UByteArray(CC_MD5_DIGEST_LENGTH)
    bytes.usePinned { src ->
        digest.usePinned { dst -> CC_MD5(src.addressOf(0), bytes.size.convert(), dst.addressOf(0)) }
    }
    return digest.joinToString("") { it.toString(16).padStart(2, '0') }
}
