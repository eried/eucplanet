package com.eried.eucplanet

import com.eried.eucplanet.util.Crc32
import com.eried.eucplanet.util.nowEpochMillis

/**
 * Tiny smoke-test surface callable from Swift, to prove the Kotlin/Native
 * `Shared` framework links and shared logic actually runs on iOS — before any
 * UI exists. The iosApp's first screen calls [selfTest] and shows the result.
 */
object SharedBootstrap {
    fun selfTest(): String {
        val crc = Crc32.compute("123456789".encodeToByteArray())
        val ok = crc == 0xCBF43926L
        return "EUC Planet shared core: CRC32 ${if (ok) "OK" else "FAIL"} " +
            "(0x${crc.toString(16).uppercase()}), t=${nowEpochMillis()}"
    }
}
