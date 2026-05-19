package com.eried.eucplanet.ble

import com.eried.eucplanet.diagnostics.DiagnosticCommand
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Begode/Gotway wheel adapter — Master / RS / EX / T4 / MSP / Hero / Mten /
 * MSX / MCM5 family on the HM-10 (0xFFE0 / 0xFFE1) BLE profile.
 *
 * Wire format (per docs/protocols/begode.md):
 *   - 24-byte BIG-ENDIAN frames `55 AA <16-byte payload> <tag> <subidx> 5A 5A 5A 5A`.
 *   - Tag at offset 18 disambiguates `0x00` Live A, `0x01..0x03` BMS, `0x04`
 *     Live B, `0x07` extras, `0xFF` SmirnoV PID.
 *   - Voltage scaling depends on per-model nominal voltage class — see
 *     [BegodeParser.voltageRatioFor].
 *
 * Outbound commands are short ASCII strings written WITHOUT response — see
 * [BegodeCommands]. Begode pushes telemetry unsolicited; init / poll loops
 * are empty.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class BegodeAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "begode"
    override val familyDisplayName = "Begode / Gotway"
    override val capabilities = WheelCapabilities.BEGODE

    @Volatile private var detectedModel: BegodeModel? = null

    /**
     * Tracks the wheel's current light state across off/on/strobe so the
     * boolean [setLight] toggle can rotate predictably. Begode is the only
     * brand with a 3-state light (spec 6.5); we collapse strobe to "on" for
     * the on/off API and skip the strobe state in the cycle.
     */
    @Volatile private var lightOn: Boolean = false

    /**
     * Lazy V/N probe state, mirroring WheelLog's GotwayAdapter.decode():
     * we ask the wheel for its firmware banner once at connect; if the
     * banner doesn't arrive (or the wheel only volunteers model), we
     * re-queue the probe each time a notification arrives until the
     * relevant field is filled. Self-disables once both are populated.
     */
    @Volatile private var detectedFirmware: String? = null
    @Volatile private var detectedModelName: String? = null
    @Volatile private var pendingProbe: ByteArray? = null

    private val parser = BegodeParser()

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        detectedModel = deviceName?.let { BegodeModel.fromReportedName(it) }
        return null
    }

    // Begode streams telemetry unsolicited (spec 5), but the firmware and
    // model-name banners only arrive in response to ASCII "V" / "N" queries.
    // WheelLog asks for them lazily inside its decode callback; we mirror
    // that with a one-shot V at connect plus an N re-queue triggered by
    // [onRawNotification] until both banners populate.
    override fun initSequence(): List<ByteArray> = listOf(BegodeCommands.queryFirmware())

    override fun pollRealtime(): ByteArray {
        val pending = pendingProbe
        if (pending != null) {
            pendingProbe = null
            return pending
        }
        return ByteArray(0)
    }

    override fun pollSettings(): ByteArray = ByteArray(0)

    override fun horn(): ByteArray = BegodeCommands.horn()

    /**
     * Begode lights are 3-state (off / on / strobe). The shared adapter API
     * is on/off only, so we map true→on (`Q`) and false→off (`E`) and skip
     * the strobe state. Strobe is reachable only via a future dedicated
     * "cycle light" UI affordance.
     */
    override fun setLight(on: Boolean): ByteArray {
        lightOn = on
        return if (on) BegodeCommands.lightOn() else BegodeCommands.lightOff()
    }

    /**
     * Begode max-speed is a 4-byte W/Y/HL/b sequence; we only return the
     * first byte here so the existing single-write [WheelAdapter] contract
     * holds. The follow-up bytes will move into a dedicated
     * paced-write extension once the connection layer grows one — for now,
     * the wheel just won't latch the new max-speed without the trailing
     * bytes, which fails safely (no setting change).
     *
     * `alarmKmh` is ignored: Begode treats `wheelMaxSpeed` as tiltback
     * threshold, with no separate alarm setter on stock FW (open question
     * 9 in spec).
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? {
        // Returning only the first packet keeps the contract intact; the rest
        // of the W-prefix sequence is built but not yet plumbed through.
        // Conservative: don't half-send a control sequence to the wheel.
        return null
    }

    override fun setVolume(percent: Int): ByteArray? = null

    // Begode has no software lock and no native DRL control. Spec 6.0 / 8.0.
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null
    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        val results = mutableListOf<DecodeResult>()

        // Detect the ASCII firmware / model banner that the wheel emits in
        // response to a "V" or "N" query. These arrive as raw text on the
        // same notify pipe as 55-AA telemetry frames, so we sniff them
        // *before* handing the chunk to the binary parser. Recognised
        // prefixes match WheelLog: GW/CFW/BF/JN/NAME-style banners.
        val ascii = tryParseBanner(rawBytes)
        if (ascii != null) {
            if (ascii.firmware != null && detectedFirmware == null) {
                detectedFirmware = ascii.firmware
                results += DecodeResult.Firmware(
                    display = ascii.firmware,
                    mainBoard = ascii.firmware,
                    driverBoard = "",
                    ble = ""
                )
            }
            if (ascii.model != null && detectedModelName == null) {
                detectedModelName = ascii.model
                val resolved = BegodeModel.fromReportedName(ascii.model)
                if (resolved != null) detectedModel = resolved
                results += DecodeResult.ModelName(ascii.model, resolved)
            }
        } else {
            results += parser.feed(rawBytes, detectedModel)
        }

        // Re-queue the next probe lazily, matching WheelLog: once firmware
        // arrives we move on to the model query; once both are filled we
        // stop asking.
        if (detectedFirmware == null) {
            pendingProbe = BegodeCommands.queryFirmware()
        } else if (detectedModelName == null) {
            pendingProbe = BegodeCommands.queryModelName()
        }

        return results
    }

    override fun onDisconnect() {
        parser.reset()
        detectedModel = null
        detectedFirmware = null
        detectedModelName = null
        pendingProbe = null
        lightOn = false
    }

    /**
     * Best-effort ASCII banner detector. Begode firmwares answer the `V`
     * query with strings like `"GW135 5.4"` or `"BF V5.3 CFW"`, and the `N`
     * query with `"NAME=MSP"` or just the model token. Anything that doesn't
     * look like printable ASCII is passed to the binary parser instead.
     */
    private data class Banner(val firmware: String?, val model: String?)

    private fun tryParseBanner(rawBytes: ByteArray): Banner? {
        if (rawBytes.isEmpty()) return null
        // Reject obvious binary frames quickly.
        if (rawBytes[0] == 0x55.toByte() && rawBytes.getOrNull(1) == 0xAA.toByte()) return null
        // Only treat as text when every byte is printable ASCII or whitespace.
        if (!rawBytes.all { it == 0x0A.toByte() || it == 0x0D.toByte() || it == 0x09.toByte() || (it in 0x20..0x7E) }) {
            return null
        }
        val text = rawBytes.toString(Charsets.US_ASCII).trim()
        if (text.isEmpty()) return null
        return when {
            text.startsWith("GW") -> Banner(firmware = text, model = null)
            text.startsWith("BF") -> Banner(firmware = text, model = null)
            text.startsWith("JN") -> Banner(firmware = text, model = null)
            text.startsWith("CF") -> Banner(firmware = text, model = null)
            text.startsWith("NAME=") -> Banner(firmware = null, model = text.removePrefix("NAME=").trim())
            text.startsWith("NAME") -> Banner(firmware = null, model = text.removePrefix("NAME").trim().removePrefix("=").trim())
            else -> null
        }
    }

    /**
     * Begode streams telemetry unsolicited at ~10 Hz; the parser logs every
     * 24-byte frame via DiagnosticsLogger.note with the `Begode realtime`
     * prefix so the Service Mode Inspect tab can scope to it. There is no
     * separate detail / extra channel — BMS, Live A, Live B and extras all
     * share the same envelope, distinguished by the tag byte at offset 18.
     */
    override fun inspectMessageTypes(): List<String> = listOf("Begode realtime")

    /**
     * Service Mode catalogue for the Begode / Gotway family. The protocol is
     * fire-and-forget ASCII (spec 6.1): commands write WRITE_NO_RESPONSE,
     * with no ack channel. The wheel only confirms via the next Live B
     * (0x04) frame or audibly. Every entry below is a control write — the
     * "queries" V and N do trigger ASCII banner replies but those land on
     * the same notify pipe as raw bytes, not framed packets.
     *
     * Each label encodes the byte sequence so a user report ("WY45b changed
     * the tiltback") maps unambiguously to a command. Max-speed writes use
     * the [BegodeCommands.setMaxSpeedSingleWrite] form: a single concatenated
     * 5-byte W-prefix sequence. Spec 6.2 expects pacing between the four
     * logical steps, so the Service Mode result also tells us whether this
     * firmware tolerates unspaced W-prefix sequences.
     */
    override fun getDiagnosticCommands(): List<DiagnosticCommand> {
        val HORN = DiagnosticCommand.Category.HORN
        val LIGHT = DiagnosticCommand.Category.LIGHT
        val MODE = DiagnosticCommand.Category.MODE
        val QUERY = DiagnosticCommand.Category.QUERY

        return listOf(
            // Control endpoint check: a beep means the wheel is reading writes.
            DiagnosticCommand("Tb_horn", "Beep horn, confirms writes land",
                BegodeCommands.horn(), HORN),

            // 3-state light (spec 6.5). Light state echoes in next 0x04 frame.
            DiagnosticCommand("TE_light_off", "Turn light off (E)",
                BegodeCommands.lightOff(), LIGHT),
            DiagnosticCommand("TQ_light_on", "Turn light on (Q)",
                BegodeCommands.lightOn(), LIGHT),
            DiagnosticCommand("TT_light_strobe", "Set light to strobe (T)",
                BegodeCommands.lightStrobe(), LIGHT),

            // Max-speed writes. Single-write W-prefix; pacing may be required.
            DiagnosticCommand("TWY25b", "Set max speed 25 km/h (unspaced)",
                BegodeCommands.setMaxSpeedSingleWrite(25), MODE),
            DiagnosticCommand("TWY35b", "Set max speed 35 km/h (unspaced)",
                BegodeCommands.setMaxSpeedSingleWrite(35), MODE),
            DiagnosticCommand("TWY45b", "Set max speed 45 km/h (unspaced)",
                BegodeCommands.setMaxSpeedSingleWrite(45), MODE),
            DiagnosticCommand("TWY80b", "Set max speed 80 km/h (unspaced)",
                BegodeCommands.setMaxSpeedSingleWrite(80), MODE),
            DiagnosticCommand("Tdq_no_max", "Disable max-speed cap (control)",
                BegodeCommands.disableMaxSpeed(), MODE),

            // ASCII banner queries. Responses arrive as raw text on notify,
            // not as framed packets, so they show up in the Inspect raw view.
            DiagnosticCommand("QV_fw", "Request firmware banner (V)",
                BegodeCommands.queryFirmware(), QUERY),
            DiagnosticCommand("QN_name", "Request model name banner (N)",
                BegodeCommands.queryModelName(), QUERY),
        )
    }
}
