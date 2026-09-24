package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.KingsongCommands
import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of a KingSong KS-18XL on firmware 2.00, replaying the frames
 * of the tester's official-app capture from issue #19 (2026-09-16), so the
 * lock can be exercised with no wheel in the room:
 *
 *  - `0x5D` with byte 2 = 1 locks; the wheel answers `0x5F 01` at once.
 *  - `0x5D` with six ASCII digits at bytes 10..15 unlocks. Nobody set a code
 *    on the tester's wheel and it took digits it had never seen, so this one
 *    takes any six digits too and reports "123456", the KingSong default.
 *    A frame without six digits leaves it locked, `0x5F 01`.
 *  - `0x5E` asks; the wheel answers `0x5F` with the state.
 *
 * Telemetry is the capture's own: 82 V pack, 31 C board, the odometer as it
 * stood, the trip frame, the name frame `KS-18L-0200`. Speed is a slow sine
 * while unlocked and pinned at zero while locked, since a locked wheel does
 * not roll (it pings when pushed; the app has no way to hear that).
 *
 * Routes via [bleName] "KS-18XL" so the dispatcher picks KingSong and
 * KingsongModel.fromReportedName picks KS18.
 */
class KingsongVirtualWheel : VirtualWheel {

    override val displayName = "Virtual KingSong 18XL (password 9111)"
    override val id = "KS18XL"
    override val bleName = "KS-18XL"

    /** Nobody set a code on this wheel, so like the tester's KS-18XL it reports
     *  the default and unlocks on any six digits. */
    val lockCode = KingsongCommands.DEFAULT_LOCK_CODE

    /** The four digits set in the KingSong app on the tester's wheel. Until
     *  they arrive in a 0x41 frame the wheel ignores 0x5D, as the real one did. */
    val password = "9111"
    var passwordAccepted = false
        private set

    var locked = false
        private set
    private var lightMode = 1
    private var lastTripTickMs = -1_000L

    override fun reset() {
        locked = false
        passwordAccepted = false
        lightMode = 1
        lastTripTickMs = -1_000L
    }

    override fun onConnect(): List<ByteArray> = listOf(NAME_FRAME.copyOf())

    override fun onWrite(data: ByteArray): List<ByteArray> {
        if (data.size < 20 || data[0] != 0xAA.toByte() || data[1] != 0x55.toByte()) return emptyList()
        return when (data[16].toInt() and 0xFF) {
            0x41 -> {
                val sent = String(data, 2, 6, Charsets.US_ASCII).trimEnd('\u0000')
                if (sent == password) {
                    passwordAccepted = true
                    listOf(ks(0x43).also { for (i in password.indices) it[2 + i] = password[i].code.toByte() })
                } else {
                    // What a real wheel answers to a wrong password is not captured.
                    emptyList()
                }
            }
            0x5D -> {
                if (!passwordAccepted) return listOf(lockStateFrame())
                if (data[2].toInt() == 1) {
                    locked = true
                } else {
                    val code = String(data, 10, 6, Charsets.US_ASCII)
                    if (KingsongCommands.isLockCode(code)) locked = false
                }
                listOf(lockStateFrame())
            }
            0x5E -> listOf(lockStateFrame())
            0x73 -> { lightMode = (data[2].toInt() and 0xFF) - 0x12; emptyList() }
            0x9B -> listOf(NAME_FRAME.copyOf())
            0x63 -> listOf(SERIAL_FRAME.copyOf())
            0x98 -> listOf(LIMITS_FRAME.copyOf())
            else -> emptyList()
        }
    }

    override fun onTick(elapsedMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        // 12 s sine, 0..15 km/h, unless locked: a locked wheel does not roll.
        val kmh = if (locked) 0f else 7.5f * (1f + sin(elapsedMs / 1000.0 * 2 * PI / 12).toFloat())
        val live = LIVE_FRAME.copyOf()
        val rawSpeed = (kmh * 100f).toInt()
        live[4] = (rawSpeed and 0xFF).toByte()
        live[5] = ((rawSpeed shr 8) and 0xFF).toByte()
        out += live
        if (elapsedMs - lastTripTickMs >= 1_000L) {
            lastTripTickMs = elapsedMs
            val trip = TRIP_FRAME.copyOf()
            trip[10] = (0x12 + lightMode).toByte()
            out += trip
        }
        return out
    }

    private fun lockStateFrame(): ByteArray = ks(0x5F).also { it[2] = if (locked) 1 else 0 }

    companion object {
        private fun ks(type: Int, payloadHex: String = ""): ByteArray {
            val f = ByteArray(20)
            f[0] = 0xAA.toByte(); f[1] = 0x55
            payloadHex.trim().takeIf { it.isNotEmpty() }?.split(Regex("\\s+"))
                ?.forEachIndexed { i, h -> f[2 + i] = h.toInt(16).toByte() }
            f[16] = type.toByte(); f[17] = 0x14; f[18] = 0x5A; f[19] = 0x5A
            return f
        }

        // Frames as the 18XL sent them on 2026-09-16 16:51 UTC.
        /** 0xA9 live: 82.18 V, 0 km/h, odometer, 0.12 A, 31.00 C, mode byte + E0 sentinel. */
        private val LIVE_FRAME = ks(0xA9, "1a 20 00 00 04 00 1d 45 0c 00 1c 0c 00 e0")
        /** 0xB9 trip: byte 10 echoes the light mode (0x12 off, 0x13 on, 0x14 auto). */
        private val TRIP_FRAME = ks(0xB9, "00 00 2b 24 0e 05 06 0b 13 00 00 00 b8 0b")
        /** 0xBB name: "KS-18L-0200", model plus firmware 2.00. */
        private val NAME_FRAME = ks(0xBB, "4b 53 2d 31 38 4c 2d 30 32 30 30 00 00 00")
        /** 0xB3 serial, first chunk. */
        private val SERIAL_FRAME = ks(0xB3, "4b 53 31 38 4c 34 44 31 39 30 31 32 35 42")
        /** 0xA4 limits as the wheel had them: alarms 15, 16, 31 km/h, tiltback 50. */
        private val LIMITS_FRAME = ks(0xA4, "01 00 0f 00 10 00 1f 00 32 00 00 00 00 00")
    }
}
