package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.InMotionV2Protocol
import com.eried.eucplanet.ble.InMotionV2Protocol.Command
import com.eried.eucplanet.ble.InMotionV2Protocol.ControlSubCmd
import com.eried.eucplanet.util.ByteUtils
import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of an InMotion P6.
 *
 * The real P6 BLE protocol differs from V14: every query is wrapped in
 * extended `02 21 [sub]` routing and responses come back as `21 02 [sub|0x80]
 * [data]`. We have BLE captures of a real P6 session locally, but the parser
 * for that format hasn't been written yet (planned for Phase 4 of the
 * multi-wheel work).
 *
 * Until then this simulator pretends the P6 speaks the V14-flavour V2
 * protocol (same wire format the existing parser handles) so the dashboard
 * can show meaningful values on a virtual P6 connection. Speed cap is 25 km/h
 * (the P6's hardware limit) and battery voltage sits around 60 V (its 60 V
 * pack) so the numbers feel right even though the framing is synthetic.
 *
 * When a real P6 parser lands, this simulator gets rewritten to emit the
 * captured extended-routing bytes and exercise that parser end-to-end.
 */
class P6VirtualWheel : VirtualWheel {

    override val displayName = "Virtual InMotion P6"
    override val id = "P6"

    private var locked = false
    private var maxSpeedKmh = 25f
    private var alarmSpeedKmh = 22f
    private var lightOn = false
    private var startTimeMs = System.currentTimeMillis()
    private var batteryStart = 90f

    override fun reset() {
        locked = false
        maxSpeedKmh = 25f
        alarmSpeedKmh = 22f
        lightOn = false
        startTimeMs = System.currentTimeMillis()
        batteryStart = 90f
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
        }
        return emptyList()
    }

    // --- Packet builders ---

    /** Identifies as P6 via the model registry (modelId 21 = series 2, type 1). */
    private fun buildCarType(): ByteArray {
        val payload = byteArrayOf(0x01, 0x06, 0x02, 0x01, 0, 0, 0, 0, 0)
        return InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, payload)
    }

    private fun buildSerial(): ByteArray =
        InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, byteArrayOf(0x02, 0x00))

    private fun buildVersions(): ByteArray {
        // Driver 5.6.7, Main 1.0.4, BLE 2.3.4
        val data = ByteArray(24).apply {
            this[0] = 0x06
            this[1 + 1] = 7;  this[1 + 2] = 0; this[1 + 3] = 6; this[1 + 4] = 5
            this[1 + 10] = 4; this[1 + 11] = 0; this[1 + 12] = 0; this[1 + 13] = 1
            this[1 + 19] = 4; this[1 + 20] = 0; this[1 + 21] = 3; this[1 + 22] = 2
        }
        return InMotionV2Protocol.buildPacket(0x14, Command.MAIN_INFO, data)
    }

    private fun buildSettings(): ByteArray {
        val d = ByteArray(36)
        d[0] = 0x20
        val ms = (maxSpeedKmh * 100).toInt()
        val asp = (alarmSpeedKmh * 100).toInt()
        d[1] = (ms and 0xFF).toByte();  d[2] = ((ms shr 8) and 0xFF).toByte()
        d[3] = (asp and 0xFF).toByte(); d[4] = ((asp shr 8) and 0xFF).toByte()
        d[21] = 0x2C; d[22] = 0x01  // standby 300s
        d[31] = 0x01                // sound on
        if (locked) d[32] = (d[32].toInt() or 0x04).toByte()
        return InMotionV2Protocol.buildPacket(0x14, Command.SETTINGS, d)
    }

    private fun buildTotalStats(): ByteArray {
        val d = ByteArray(20)
        // P6 is a city wheel: give it modest mileage of 320 km
        putUint32LE(d, 0, 32_000)        // ÷10 → 320 km equivalent in meters
        putUint32LE(d, 12, 40 * 3600)
        putUint32LE(d, 16, 80 * 3600)
        return InMotionV2Protocol.buildPacket(0x14, Command.TOTAL_STATS, d)
    }

    private fun buildTelemetry(): ByteArray {
        val elapsed = System.currentTimeMillis() - startTimeMs
        // P6 cap is 25 km/h: sine wave 0..20 km/h on a 12s period.
        val speedKmh = if (locked) 0f else (10f * (1f + sin(elapsed / 1000.0 * 2 * PI / 12).toFloat()))
        val batteryPct = (batteryStart - elapsed / 90_000f).coerceAtLeast(20f)
        // P6 uses a 60 V pack: 54 V at 0 %, 60 V at 90 %.
        val voltage = 54f + (batteryPct - 0f) * (6f / 90f)
        val current = if (speedKmh > 1f) 4f else 0.3f
        val pwm = if (speedKmh > 1f) 20f + speedKmh * 0.8f else 0f

        val d = ByteArray(80)
        putInt16LE(d, 0,  (voltage * 100).toInt())
        putInt16LE(d, 2,  (current * 100).toInt())
        putInt16LE(d, 8,  (speedKmh * 100).toInt())
        putInt16LE(d, 14, (pwm * 100).toInt())
        putInt16LE(d, 16, (voltage * current).toInt())
        putInt16LE(d, 18, (voltage * current).toInt())
        putInt16LE(d, 34, (batteryPct * 100).toInt())
        putInt16LE(d, 36, (batteryPct * 100).toInt())
        putInt16LE(d, 40, (maxSpeedKmh * 100).toInt())
        putInt16LE(d, 50, 1500)                          // 15 A current limit
        // P6 runs cooler than V14: ~30 °C on a normal ride
        d[58] = 206.toByte() // 30°C
        d[59] = 208.toByte() // 32°C
        d[60] = 207.toByte() // 31°C
        d[61] = 205.toByte() // 29°C
        d[62] = 204.toByte() // 28°C
        d[63] = 206.toByte() // 30°C
        d[74] = if (locked) 0x00 else 0x01
        d[76] = if (lightOn) 0x02 else 0x00

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

    private fun putUint32LE(target: ByteArray, offset: Int, value: Int) =
        putUint32LE(target, offset, value.toLong())
}
