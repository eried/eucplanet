package com.eried.eucplanet.ble

import com.eried.eucplanet.diagnostics.DiagnosticCommand
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IPS family adapter (IPS i5 / Zero / Lhotz / XIMA, and the S5 / T350 siblings).
 *
 * IPS was one of the earliest EUC brands (Shenzhen / Singapore, defunct since
 * ~2017), so there is NO official app to update against and NO open-source
 * decoder for it: WheelLog never supported IPS. Everything here is derived
 * from a community BLE sniff of an IPS XIMA / Lhotz posted in 2015
 * (forum.electricunicycle.org topic 1133) plus our own captures. See
 * docs/protocols/ips_i5.md for the full write-up and attribution.
 *
 * STATUS: capture phase. What is confirmed from the XIMA/Lhotz sniff:
 *  - GATT profile: service 0xFF00, notify 0xFF01 (subscribe), write 0xFF02.
 *  - Command frame: write `90 00 <opcode>` to FF02; the wheel answers on FF01
 *    with a frame that begins with the same `90 00 <opcode>`, and several
 *    answers can be packed into one notification.
 *  - `90 00 01` is the speed poll (the app fires it ~2 Hz).
 *  - `90 00 10` / `90 00 11` carry the other values (battery / mileage /
 *    firmware) but their byte layout and scaling were never nailed down
 *    publicly.
 *
 * So this adapter's job right now is to CONNECT, subscribe, poll the known
 * read opcodes, and log every framed answer to diagnostics so a tester ride
 * gives us a clean labelled capture to derive the offsets from. It does NOT
 * fabricate telemetry values (that would put invented numbers on the
 * dashboard and waste a tester's time); it emits [DecodeResult.Unknown] until
 * the i5 layout is confirmed, at which point the parse fills in here.
 *
 * The i5 in particular is a smaller / later model than the XIMA that was
 * sniffed, so even the FF00 profile above must be CONFIRMED against a real i5
 * capture before we trust it. The name matcher in [wheelFamilyForName] and
 * [isLikelyWheelName] is a best guess until a tester reports the exact
 * advertised name.
 *
 * No control commands are wired yet (horn / light / lock / speed limit all
 * return null and [capabilities] declares none) because sending guessed write
 * opcodes to a real wheel is unsafe. Reads only until the protocol is mapped.
 */
@Singleton
class IpsAdapter @Inject constructor() : WheelAdapter {

    override val familyId = "ips"
    override val familyDisplayName = "IPS"
    override val capabilities = WheelCapabilities.IPS_I5

    override fun bleProfile(): BleProfile = BleProfile.IPS

    override fun initSequence(): List<ByteArray> = emptyList()

    // Speed poll every 250 ms tick. `90 00 01` is the one opcode the 2015
    // XIMA sniff identified with confidence (fired at the same ~2 Hz cadence
    // the app's speedometer refreshed).
    override fun pollRealtime(): ByteArray = readFrame(OP_SPEED)

    // Rotate the two candidate "other value" reads on the slower settings
    // tick so a capture maps what each answers without spamming the wheel.
    private var settingsToggle = false
    override fun pollSettings(): ByteArray {
        settingsToggle = !settingsToggle
        return readFrame(if (settingsToggle) OP_INFO_10 else OP_INFO_11)
    }

    // No decoded control set yet. See class doc: reads only until mapped.
    override fun horn(): ByteArray? = null
    override fun setLight(on: Boolean): ByteArray? = null
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = null
    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null
    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * The wheel answers on FF01 with one or more `90 00 <opcode> <payload>`
     * frames concatenated together. Split them on the `90 00` marker and log
     * each with its opcode so the capture reads as discrete, labelled frames
     * (the raw notification is already logged by the connection manager; this
     * adds the per-opcode framing that makes the byte layout derivable).
     *
     * Returns [DecodeResult.Unknown]: the payload scaling for the i5 is not
     * confirmed, and we do not put invented numbers on the dashboard. Once a
     * labelled i5 capture pins the offsets, decode them here and emit
     * [DecodeResult.Telemetry].
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        for ((opcode, payload) in splitFrames(rawBytes)) {
            DiagnosticsLogger.note(
                "ips frame op=90 00 ${hex1(opcode)} payload=${hexBytes(payload)}"
            )
        }
        return listOf(DecodeResult.Unknown)
    }

    override fun inspectMessageTypes(): List<String> = listOf("ips")

    /**
     * Read probes for Service Mode: a tester taps one, watches the live log,
     * and reports what the wheel returned. This is how we map opcode -> value
     * for the i5 without a manufacturer app. All QUERY (read) frames -- no
     * writes that could change a setting.
     */
    override fun getDiagnosticCommands(): List<DiagnosticCommand> {
        val q = DiagnosticCommand.Category.QUERY
        return (0x00..0x20).map { op ->
            DiagnosticCommand(
                label = "R${hex1(op.toByte())}",
                description = "Read 90 00 ${hex1(op.toByte())} (probe response)",
                bytes = readFrame(op.toByte()),
                category = q,
            )
        }
    }

    private fun readFrame(opcode: Byte): ByteArray = byteArrayOf(0x90.toByte(), 0x00, opcode)

    /** Split a notification into (opcode, payload) pairs on the `90 00` marker. */
    private fun splitFrames(bytes: ByteArray): List<Pair<Byte, ByteArray>> {
        val out = mutableListOf<Pair<Byte, ByteArray>>()
        var i = 0
        while (i + 2 < bytes.size) {
            if (bytes[i] == 0x90.toByte() && bytes[i + 1] == 0x00.toByte()) {
                val opcode = bytes[i + 2]
                var j = i + 3
                // Payload runs until the next `90 00` marker or end of buffer.
                while (j + 1 < bytes.size && !(bytes[j] == 0x90.toByte() && bytes[j + 1] == 0x00.toByte())) j++
                out += opcode to bytes.copyOfRange(i + 3, minOf(j + 1, bytes.size).coerceAtLeast(i + 3))
                i = j
            } else i++
        }
        return out
    }

    private fun hex1(b: Byte): String = "%02x".format(b.toInt() and 0xff)
    private fun hexBytes(b: ByteArray): String = b.joinToString(" ") { hex1(it) }

    private companion object {
        const val OP_SPEED: Byte = 0x01
        const val OP_INFO_10: Byte = 0x10
        const val OP_INFO_11: Byte = 0x11
    }
}
