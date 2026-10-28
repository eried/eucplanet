package com.eried.eucplanet.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray = memScoped {
    val out = allocArray<UByteVar>(CC_SHA256_DIGEST_LENGTH)
    if (data.isEmpty()) CC_SHA256(null, 0u, out)
    else data.usePinned { CC_SHA256(it.addressOf(0), data.size.toUInt(), out) }
    ByteArray(CC_SHA256_DIGEST_LENGTH) { out[it].toByte() }
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomInt(bound: Int): Int {
    require(bound > 0) { "bound must be positive" }
    // Rejection sampling over 31 random bits, so every value is equally likely.
    val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound)
    while (true) {
        val bytes = ByteArray(4)
        bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, 4u, it.addressOf(0)) }
        val v = ((bytes[0].toInt() and 0x7F) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        if (v < limit) return v % bound
    }
}
