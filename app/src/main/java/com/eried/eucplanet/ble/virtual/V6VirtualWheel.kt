package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.InMotionV2Protocol
import com.eried.eucplanet.ble.InMotionV2Protocol.Command
import kotlin.math.PI
import kotlin.math.sin

/**
 * Software fake of an InMotion V6, replaying the wheel's REAL dialect as
 * captured from the official app against V6-700326F3: info queries arrive as
 * `13 04 02 00 02 [sub]` and are answered with `12 .. 02 82 [sub] [data]`;
 * the realtime poll `02 21 04` is answered with the extended `21 02 84`
 * frame (66-byte body); the stats poll `02 21 11` with `21 02 91`.
 *
 * Unlike [P6VirtualWheel] (which predates the extended parser and still
 * emits V14-shaped frames), this simulator speaks the same bytes as the real
 * wheel, so a virtual connection exercises the whole V6 path: the name-bound
 * dialect switch, the 0x13 queries, and parseV6Telemetry / parseV6TotalKm.
 *
 * The telemetry body is a genuine frame template from the capture with the
 * live fields (voltage, current, speed, trip, temperature, rider marker)
 * patched per poll: a sine-wave speed 0..20 km/h, a slowly draining 13S pack
 * and a trip that integrates the simulated speed.
 */
class V6VirtualWheel : VirtualWheel {

    override val displayName = "Virtual InMotion V6"
    override val id = "V6"
    override val bleName = "V6-VIRTUAL1"

    private var startTimeMs = System.currentTimeMillis()
    private var totalKm0 = 7.53f

    /** Fixed 16-byte session value handed out by the auth-key query; the
     *  wheel accepts only an exact echo of it. Synthetic, not a captured one. */
    private val sessionKey = ByteArray(16) { (0x10 + it).toByte() }

    override fun reset() {
        startTimeMs = System.currentTimeMillis()
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        val packet = InMotionV2Protocol.parsePacket(data) ?: return emptyList()
        val cmd = packet.command.toInt() and 0x7F
        val d = packet.data

        // Session handshake, same shape as the real wheel: the auth-key query
        // (data [00 00 02]) gets a 16-byte session value, and echoing that
        // value back (data [00 00 82] + 16 bytes) gets the success ack. The
        // real V6 requires this exchange every ~6 s to keep answering, so the
        // simulator serves it to exercise the repository's connect-auth path.
        if (cmd == (Command.MAIN_INFO.toInt() and 0x7F) && d.size == 3 &&
            d[0].toInt() == 0x00 && d[1].toInt() == 0x00 && d[2].toInt() == 0x02
        ) {
            return listOf(
                InMotionV2Protocol.buildPacket(
                    0x12, Command.MAIN_INFO,
                    byteArrayOf(0x80.toByte(), 0x02) + sessionKey
                )
            )
        }
        if (cmd == (Command.MAIN_INFO.toInt() and 0x7F) && d.size >= 19 &&
            d[0].toInt() == 0x00 && d[1].toInt() == 0x00 && (d[2].toInt() and 0xFF) == 0x82
        ) {
            val echoed = d.copyOfRange(3, 19)
            val ok = echoed.contentEquals(sessionKey)
            return listOf(
                InMotionV2Protocol.buildPacket(
                    0x12, Command.MAIN_INFO,
                    byteArrayOf(0x80.toByte(), 0x82.toByte(), if (ok) 0x01 else 0x00)
                )
            )
        }
        // 0x13-wrapped info query: command 0x02, data = [0x00, 0x02, sub].
        if (cmd == (Command.MAIN_INFO.toInt() and 0x7F) && d.size >= 3 &&
            d[0].toInt() == 0x00 && d[1].toInt() == 0x02
        ) {
            return when (d[2].toInt() and 0xFF) {
                0x01 -> listOf(reply12(0x01, byteArrayOf(0x02, 0x0e, 0x01, 0x01, 0x01, 0x00)))
                0x02 -> listOf(reply12(0x02, "A1421A41VIRTUAL1".toByteArray(Charsets.US_ASCII)))
                0x06 -> listOf(reply12(0x06, ByteArray(24)))
                else -> emptyList()
            }
        }
        // Extended query: buildExtendedPacket puts the 0x02 routing byte in
        // the command slot, so the frame parses as command 0x02 with
        // data = [0x21, sub]. (Responses go the other way round: 0x21 in the
        // command slot, [0x02, sub|0x80] in the data.)
        if (cmd == (Command.MAIN_INFO.toInt() and 0x7F) && d.size >= 2 && d[0].toInt() == 0x21) {
            return when (d[1].toInt() and 0xFF) {
                0x04 -> listOf(buildTelemetry())
                0x11 -> listOf(buildTotalStats())
                else -> emptyList()
            }
        }
        return emptyList()
    }

    /** `aa aa 12 len 02 82 [sub] [data] ck`, the V6 info-reply shape. */
    private fun reply12(sub: Int, data: ByteArray): ByteArray =
        InMotionV2Protocol.buildPacket(
            0x12, Command.MAIN_INFO,
            byteArrayOf(0x82.toByte(), sub.toByte()) + data
        )

    /** A real captured 66-byte `02 84` body (idle, 51.30 V) as the template. */
    private val telemetryTemplate = byteArrayOf(
        0x0a, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x68, 0xef.toByte(), 0x68,
        0xef.toByte(), 0x2f, 0xff.toByte(), 0x00, 0x00, 0x75, 0x1b, 0xd8.toByte(), 0x1a, 0xb8.toByte(),
        0x0b, 0x54, 0x09, 0xdc.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0xb0.toByte(), 0xca.toByte(), 0xb0.toByte(), 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    private fun buildTelemetry(): ByteArray {
        val elapsed = System.currentTimeMillis() - startTimeMs
        val speedKmh = (10f * (1f + sin(elapsed / 1000.0 * 2 * PI / 12).toFloat()))
        // 13S pack drains slowly from 51.3 V; percent derives from voltage.
        val voltage = (51.3f - elapsed / 600_000f).coerceAtLeast(45f)
        val current = if (speedKmh > 1f) 3f else 0.2f
        val tripKm = (elapsed / 1000f) * 10f / 3600f // avg 10 km/h

        val body = telemetryTemplate.copyOf()
        putInt16LE(body, 0, (voltage * 100).toInt())
        putInt16LE(body, 2, (current * 100).toInt())
        putInt16LE(body, 4, (speedKmh * 100).toInt())
        // Duty against the model's 30 km/h ceiling, the relationship the
        // capture shows, so the dashboard's PWM tile moves with the speed.
        putInt16LE(body, 8, (speedKmh / 30f * 100f * 100f).toInt())
        putInt16LE(body, 22, (tripKm * 100).toInt())
        body[45] = (-50).toByte() // TempOffset80: 30 C
        body[54] = if (speedKmh > 1f) 0x49 else 0x00
        return InMotionV2Protocol.buildPacket(
            InMotionV2Protocol.Flags.EXTENDED, 0x21,
            byteArrayOf(0x02, 0x84.toByte()) + body
        )
    }

    private fun buildTotalStats(): ByteArray {
        val elapsed = System.currentTimeMillis() - startTimeMs
        val totalKm = totalKm0 + (elapsed / 1000f) * 10f / 3600f
        val body = ByteArray(24)
        putUint32LE(body, 0, (totalKm * 100).toInt())
        putUint32LE(body, 12, 3212) // lifetime riding seconds, from the capture
        return InMotionV2Protocol.buildPacket(
            InMotionV2Protocol.Flags.EXTENDED, 0x21,
            byteArrayOf(0x02, 0x91.toByte()) + body
        )
    }

    private fun putInt16LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putUint32LE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
