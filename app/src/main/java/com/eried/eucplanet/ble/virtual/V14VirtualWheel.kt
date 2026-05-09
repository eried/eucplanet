package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.InMotionV2Protocol
import com.eried.eucplanet.ble.InMotionV2Protocol.Command
import com.eried.eucplanet.ble.InMotionV2Protocol.ControlSubCmd
import com.eried.eucplanet.util.ByteUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of an InMotion V14 (50GB). Reports model "V14 50GB", firmware
 * Main 1.2.3 / Driver 4.5.6 / BLE 7.8.9, then on every realtime poll emits a
 * telemetry frame with an oscillating speed (sine 0..30 km/h over ~10 s),
 * static 84 V battery at 80 %, and a lazy battery decay so the dashboard
 * looks alive over time.
 *
 * Settings poll returns max speed 50 / alarm 40, lock state matching the
 * `locked` field. Lock and tiltback control writes update internal state
 * which the next settings poll surfaces — the same loop a real wheel uses.
 *
 * Auth handshake: when the app sends requestAuthKey we return a fixed 16-byte
 * "encrypted" key; verifyAuth always succeeds. Real wheels do AES against a
 * preset password — we don't need that to exercise the parsing pipeline.
 */
class V14VirtualWheel : VirtualWheel {

    override val displayName = "Virtual InMotion V14 (50GB)"
    override val id = "V14"

    private var locked = false
    private var maxSpeedKmh = 50f
    private var alarmSpeedKmh = 40f
    private var lightOn = false
    private var startTimeMs = System.currentTimeMillis()
    private var batteryStart = 80f

    override fun reset() {
        locked = false
        maxSpeedKmh = 50f
        alarmSpeedKmh = 40f
        lightOn = false
        startTimeMs = System.currentTimeMillis()
        batteryStart = 80f
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        val packet = InMotionV2Protocol.parsePacket(data) ?: return emptyList()
        val cmd = packet.command.toInt() and 0x7F
        val sub = packet.data.firstOrNull()?.toInt()?.and(0xFF)

        return when {
            cmd == (Command.MAIN_INFO.toInt() and 0xFF) && sub == 0x01 -> listOf(buildCarType())
            cmd == (Command.MAIN_INFO.toInt() and 0xFF) && sub == 0x02 -> listOf(buildSerial())
            cmd == (Command.MAIN_INFO.toInt() and 0xFF) && sub == 0x06 -> listOf(buildVersions())
            cmd == (Command.SETTINGS.toInt() and 0xFF) -> listOf(buildSettings())
            cmd == (Command.SOMETHING1.toInt() and 0xFF) -> emptyList()
            cmd == (Command.TOTAL_STATS.toInt() and 0xFF) -> listOf(buildTotalStats())
            cmd == (Command.REAL_TIME_INFO.toInt() and 0xFF) -> listOf(buildTelemetry())
            cmd == (Command.CONTROL.toInt() and 0xFF) -> handleControl(packet.data)
            else -> emptyList()
        }
    }

    private fun handleControl(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        when (data[0]) {
            ControlSubCmd.SET_LIGHT -> {
                if (data.size > 1) lightOn = data[1].toInt() == 0x01
            }
            ControlSubCmd.SET_LOCK -> {
                if (data.size > 1) locked = data[1].toInt() == 0x01
            }
            ControlSubCmd.SET_MAX_SPEED -> {
                if (data.size >= 5) {
                    maxSpeedKmh = ByteUtils.getUint16LE(data, 1) / 100f
                    alarmSpeedKmh = ByteUtils.getUint16LE(data, 3) / 100f
                }
            }
            // SET_VOLUME / SET_DRL / PLAY_SOUND — accept silently
        }
        return emptyList()
    }

    // --- Packet builders ---

    private fun buildCarType(): ByteArray {
        // V14 50GB = series 9, type 1. Eight bytes of carInfo are required.
        val payload = byteArrayOf(0x01, 0x09, 0x09, 0x01, 0, 0, 0, 0, 0)
        return InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, payload)
    }

    private fun buildSerial(): ByteArray {
        // Repository doesn't act on serial; minimal echo is enough.
        return InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, byteArrayOf(0x02, 0x00))
    }

    private fun buildVersions(): ByteArray {
        // parseVersions reads at offsets 1, 10, 19 (each: patch16-LE, minor, major)
        // Want: Driver 7.8.9, Main 1.2.3, BLE 4.5.6
        val data = ByteArray(24).apply {
            this[0] = 0x06  // sub-cmd echo
            // Driver @ offset 1: patch=9, minor=8, major=7
            this[1 + 1] = 9; this[1 + 2] = 0; this[1 + 3] = 8; this[1 + 4] = 7
            // Main @ offset 10
            this[1 + 10] = 3; this[1 + 11] = 0; this[1 + 12] = 2; this[1 + 13] = 1
            // BLE @ offset 19
            this[1 + 19] = 6; this[1 + 20] = 0; this[1 + 21] = 5; this[1 + 22] = 4
        }
        return InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, data)
    }

    private fun buildSettings(): ByteArray {
        // Layout (after the 0x20 sub-cmd echo): 35 bytes minimum
        // 0..1   maxSpeed       uint16-LE × 100
        // 2..3   alarmSpeed     uint16-LE × 100
        // 8..9   pedalAdjust    int16-LE × 10
        // 10     ride mode bits (bit 0 = offroad, bit 4 = fancier)
        // 20..21 standbyDelay   seconds
        // 30     bit 0 = sound on (mute = !bit), bit 2 = drl
        // 31     bit 2 = locked, bit 4 = transport
        val d = ByteArray(36)
        d[0] = 0x20  // sub-cmd echo
        val ms = (maxSpeedKmh * 100).toInt()
        val asp = (alarmSpeedKmh * 100).toInt()
        d[1] = (ms and 0xFF).toByte();  d[2] = ((ms shr 8) and 0xFF).toByte()
        d[3] = (asp and 0xFF).toByte(); d[4] = ((asp shr 8) and 0xFF).toByte()
        d[21] = 0x2C  // standbyDelay LE = 300 s = 5 min
        d[22] = 0x01
        d[31] = 0x01  // sound on
        if (locked) d[32] = (d[32].toInt() or 0x04).toByte()
        return InMotionV2Protocol.buildPacket(0x14, Command.SETTINGS, d)
    }

    private fun buildTotalStats(): ByteArray {
        // parseTotalStats: distance@0 (×10 → meters), rideTime@12, powerOnTime@16
        val d = ByteArray(20)
        // Total odometer: 1234.5 km = 1,234,500 m → stored / 10 = 123450
        putUint32LE(d, 0, 123_450)
        putUint32LE(d, 12, 100 * 3600)   // 100 hours ride time
        putUint32LE(d, 16, 200 * 3600)   // 200 hours power-on time
        return InMotionV2Protocol.buildPacket(0x14, Command.TOTAL_STATS, d)
    }

    private fun buildTelemetry(): ByteArray {
        val elapsed = System.currentTimeMillis() - startTimeMs
        // Sine wave 0..30 km/h with a 10 s period, but locked wheel = 0
        val speedKmh = if (locked) 0f else (15f * (1f + sin(elapsed / 1000.0 * 2 * PI / 10).toFloat()))
        // Battery slowly decays, ~1 % per minute
        val batteryPct = (batteryStart - elapsed / 60_000f).coerceAtLeast(20f)
        val voltage = 84f - (80f - batteryPct) * 0.1f  // roughly 76 V at 0 %, 84 V at 80 %

        val current = if (speedKmh > 1f) 8f else 0.5f
        val pwm = if (speedKmh > 1f) 25f + speedKmh else 0f

        val d = ByteArray(80)
        putInt16LE(d, 0,  (voltage * 100).toInt())
        putInt16LE(d, 2,  (current * 100).toInt())
        putInt16LE(d, 8,  (speedKmh * 100).toInt())
        putInt16LE(d, 12, 0)                            // torque
        putInt16LE(d, 14, (pwm * 100).toInt())
        putInt16LE(d, 16, (voltage * current).toInt())  // battery power W
        putInt16LE(d, 18, (voltage * current).toInt())  // motor power W
        putInt16LE(d, 20, 0)                            // pitch
        putInt16LE(d, 22, 0)                            // roll
        putInt16LE(d, 28, 0)                            // trip distance (m / 10)
        putInt16LE(d, 34, (batteryPct * 100).toInt())   // battery 1 (×100)
        putInt16LE(d, 36, (batteryPct * 100).toInt())   // battery 2
        putInt16LE(d, 40, (maxSpeedKmh * 100).toInt())  // dynamic speed limit
        putInt16LE(d, 50, 3000)                         // dynamic current limit (×100 = 30 A)
        // Temperatures: encoded as raw + 80 - 256, so 0°C = 176 (0xB0)
        // 35°C = 211, 40°C = 216 — use a spread for the six sensors
        d[58] = 211.toByte() // 35°C
        d[59] = 213.toByte() // 37°C
        d[60] = 215.toByte() // 39°C
        d[61] = 210.toByte() // 34°C
        d[62] = 209.toByte() // 33°C
        d[63] = 212.toByte() // 36°C
        d[74] = if (locked) 0x00 else 0x01  // pcMode: 0=lock, 1=drive
        d[76] = if (lightOn) 0x02 else 0x00 // light bit 1

        return InMotionV2Protocol.buildPacket(0x14, Command.REAL_TIME_INFO, d)
    }

    private fun putInt16LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putUint32LE(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun putUint32LE(target: ByteArray, offset: Int, value: Int) {
        putUint32LE(target, offset, value.toLong())
    }
}
