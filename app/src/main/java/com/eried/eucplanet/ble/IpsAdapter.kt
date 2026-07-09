package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IPS family adapter (IPS i5 and eWheel-service siblings).
 *
 * IPS is a defunct EUC brand (Shenzhen / Singapore, ~2017) with no app updates
 * and, unlike every other family, no WheelLog decoder. The protocol here is
 * decoded from a static read of the official iAmIPS 4.4.2 app
 * (com.iamips.ipsapp); the full write-up is docs/protocols/ips_i5.md.
 *
 * The i5 is a CHARACTERISTIC-PER-VALUE wheel: each field is its own GATT
 * characteristic under the eWheel service (0xEB00) that the app subscribes to
 * (speed 9002, current 9003) or reads on a timer (trip 9004, total 9005,
 * battery 9006, cell voltages 9007, temp 9008, max speed 900a, info 900d, ...).
 * Speed / trip / total are plain little-endian float32 already in km/h and km.
 *
 * ARCHITECTURE NOTE: the current [WheelAdapter] / [BleProfile] model assumes
 * one write char + one notify stream parsed by [onRawNotification]. That does
 * not fit a per-value wheel. Full i5 support needs a BLE-layer extension so an
 * adapter can declare a set of notify + periodically-read characteristics and
 * receive each result tagged with its source UUID (a future
 * `onCharacteristicData(uuid, bytes)` hook). Until that exists this adapter
 * connects and logs, keeps the authoritative UUID map + decoders in one place,
 * and emits nothing invented to the dashboard. When the extension lands,
 * [decodeCharacteristic] below is the parse - just route real per-UUID data
 * into it.
 */
@Singleton
class IpsAdapter @Inject constructor() : WheelAdapter {

    override val familyId = "ips"
    override val familyDisplayName = "IPS"
    override val capabilities = WheelCapabilities.IPS_I5

    override fun bleProfile(): BleProfile = BleProfile.IPS

    override fun initSequence(): List<ByteArray> = emptyList()
    // No command-poll: the i5 pushes speed/current via notify and is read
    // per-characteristic. Nothing to write on the realtime tick.
    override fun pollRealtime(): ByteArray = ByteArray(0)
    override fun pollSettings(): ByteArray = ByteArray(0)

    // No decoded control set wired yet (needs the multi-char extension + an
    // i5 capture to confirm the mode/limit code tables). The app supports
    // light mode (9009), max-speed limit (900a) and run mode (900b) as
    // single-byte writes; enable them here once routed and confirmed.
    override fun horn(): ByteArray? = null
    override fun setLight(on: Boolean): ByteArray? = null
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = null
    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null
    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * Single-stream entry point. The i5 does not use one notification stream,
     * so under today's architecture this only sees whatever the notify char in
     * [BleProfile.IPS] (speed, 0x9002) delivers. Log it for capture and emit
     * Unknown; real decoding happens per-characteristic in [decodeCharacteristic]
     * once the BLE extension routes each UUID here.
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        DiagnosticsLogger.note("ips notify(9002 speed?) ${hexBytes(rawBytes)}")
        return listOf(DecodeResult.Unknown)
    }

    override fun inspectMessageTypes(): List<String> = listOf("ips")

    /**
     * Authoritative per-characteristic decode, decoded from iAmIPS 4.4.2.
     * Merges into [last] and returns the running snapshot. Not yet reachable
     * (needs the multi-char BLE extension); kept here so wiring it up is a
     * one-line call site once per-UUID data is delivered.
     */
    fun decodeCharacteristic(uuid: UUID, v: ByteArray): DecodeResult? {
        last = when (uuid) {
            SPEED -> if (v.size >= 4) last.copy(speed = floatLE(v, 0)) else return null
            CURRENT -> if (v.size >= 5) last.copy(current = floatLE(v, 1)) else return null
            TRIP -> if (v.size >= 4) last.copy(tripDistance = floatLE(v, 0)) else return null
            TOTAL -> if (v.size >= 4) last.copy(totalDistance = floatLE(v, 0)) else return null
            BATTERY -> if (v.isNotEmpty()) last.copy(batteryPercent = v[0].toInt() and 0xFF) else return null
            TEMP -> if (v.size >= 4) last.copy(maxTemperature = floatLE(v, 0),
                temperatures = listOf(floatLE(v, 0))) else return null
            VOLTAGE -> if (v.size >= 4) {
                // 16 per-cell float32s; pack voltage is their sum.
                val cells = (0 until v.size / 4).map { floatLE(v, it * 4) }
                last.copy(voltage = cells.sum())
            } else return null
            else -> return null
        }
        return DecodeResult.Telemetry(last.copy(timestamp = System.currentTimeMillis()))
    }

    override fun onDisconnect() { last = WheelData() }

    @Volatile private var last = WheelData()

    /** Little-endian IEEE-754 float32 at [off], matching the app's Utils.getFloat. */
    private fun floatLE(b: ByteArray, off: Int): Float {
        val bits = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
        return Float.fromBits(bits)
    }

    private fun hexBytes(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        // eWheel service (0xEB00) characteristics, from iAmIPS 4.4.2.
        private fun ch(x: String) = UUID.fromString("0000$x-0000-1000-8000-00805f9b34fb")
        val SPEED = ch("9002")     // float32 LE km/h
        val CURRENT = ch("9003")   // float32 LE @1, amps
        val TRIP = ch("9004")      // float32 LE km
        val TOTAL = ch("9005")     // float32 LE km
        val BATTERY = ch("9006")   // byte %,
        val VOLTAGE = ch("9007")   // 16x float32 LE cell voltages
        val TEMP = ch("9008")      // float32 LE degC
    }
}
