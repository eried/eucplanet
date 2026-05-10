package com.eried.eucplanet.ble

import android.util.Log
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WheelAdapter for the InMotion V2 protocol family — V11, V12HS/HT/PRO/S, V13, V14.
 *
 * Phase 1 wraps the existing [InMotionV2Commands] / [InMotionV2Parser] / [InMotionV2Protocol]
 * objects without changing their behavior. Phase 2 will fold model-conditional logic
 * (V11 vs V12 vs V14 parsers, beep vs sound horn, short vs extended max-speed packet)
 * into this class once those models actually need it.
 *
 * The decode dispatch mirrors the legacy WheelRepository.handlePacket() switch
 * exactly so the refactor is provably no-op for V14.
 */
@Singleton
class InMotionV2Adapter @Inject constructor() : WheelAdapter {

    override val familyId: String = "inmotion_v2"
    override val capabilities: WheelCapabilities = WheelCapabilities.INMOTION_V2

    /**
     * Detected model from the wheel's MainInfo response. Set the first time
     * [decode] sees a CarType packet on each connection.
     *
     * Volatile because decode runs on the BLE coroutine and reads happen from
     * the main thread. Cleared to null on disconnect.
     */
    @Volatile var detectedModel: InMotionV2Model? = null
        private set

    /**
     * Use the P6's extended-routing-only command set. Only set by
     * [notifyConnectingTo] from the BLE name (`P6-XXXXXXXX`) and never flipped
     * by telemetry — keeping it name-bound means the virtual P6 simulator,
     * which emits V14-shaped packets, can keep using the V14 command path even
     * after carType identifies it as a P6.
     */
    @Volatile private var useP6Protocol: Boolean = false

    /**
     * BLE notifications can split a single AA AA frame across multiple packets.
     * Buffer them here and scan for complete frames. Stays in this adapter so
     * sibling adapters (V1, KingSong, Veteran) can keep their own framing state
     * without interference.
     */
    private val reassemblyBuffer = ByteArrayOutputStream()

    /**
     * Pre-select model from the BLE advertised name. The InMotion P6 uses an
     * extended-routing-only command set: the legacy `02 [cmd]` queries return
     * all-zero blobs (verified in real-hardware captures), so we have to know
     * we're talking to a P6 *before* sending the first init packet. The name
     * `P6-XXXXXXXX` is the cleanest pre-connect signal — we set the model now
     * and let [initSequence] / [pollRealtime] / [decode] take the P6 branch.
     */
    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        if (deviceName != null && deviceName.startsWith("P6-")) {
            detectedModel = InMotionV2Model.P6
            useP6Protocol = true
            // Surface the model right away so _maxSpeedCap and other
            // model-keyed UI updates don't wait for the wheel's info-bundle
            // round-trip. The serial fills in later when 0x06 lands.
            return DecodeResult.ModelName("InMotion P6", InMotionV2Model.P6)
        }
        return null
    }

    override fun initSequence(): List<ByteArray> {
        // P6: query info bundle for serial, then settings page A so the UI
        // shows the current tiltback. Telemetry kicks in via pollRealtime.
        if (useP6Protocol) {
            return listOf(
                InMotionV2Commands.getP6Info(),
                InMotionV2Commands.getP6Settings()
            )
        }
        return listOf(
            InMotionV2Commands.getCarType(),
            InMotionV2Commands.getSerialNumber(),
            InMotionV2Commands.getVersions(),
            InMotionV2Commands.getCurrentSettings(),
            InMotionV2Commands.getUselessData(),
            InMotionV2Commands.getStatistics()
        )
    }

    override fun pollRealtime(): ByteArray =
        if (useP6Protocol) InMotionV2Commands.getP6RealTimeData()
        else InMotionV2Commands.getRealTimeData()

    /**
     * Periodic settings refresh. The P6 returns a 51-byte settings page on
     * `02 21 20 [20]`; the parser pulls the current tiltback at offset 13-14.
     */
    override fun pollSettings(): ByteArray =
        if (useP6Protocol) InMotionV2Commands.getP6Settings()
        else InMotionV2Commands.getCurrentSettings()

    /**
     * Horn dispatch. V14 family models (V14g/V14s/V13/V13PRO/V11Y) use the
     * verified [InMotionV2Commands.horn] (playBeep variant). Older models
     * (V11/V12HS/HT/PRO/V12S/V9) need the playSound variant. When the model
     * isn't yet known the V14 path is the safer default.
     */
    override fun horn(): ByteArray {
        if (useP6Protocol) return InMotionV2Commands.hornP6()
        val m = detectedModel
        return if (m == null || m.hornOpcode == InMotionV2Model.HORN_PLAY_BEEP) {
            InMotionV2Commands.horn()
        } else {
            InMotionV2LegacyCommands.playSoundHorn()
        }
    }

    override fun setLight(on: Boolean): ByteArray =
        if (useP6Protocol) InMotionV2Commands.setP6Light(on)
        else InMotionV2Commands.setLight(on)

    /**
     * Max speed dispatch. Models that can carry alarm thresholds in the same
     * packet (V14 family + V13/V13PRO/V12S/V9/V11Y) use the V14 long form.
     * Older V11/V12HS/HT/PRO accept only tiltback in a 2-byte payload — the
     * alarmKmh argument is ignored for those models, since the wheel won't
     * accept it on this packet. The UI already keeps tiltback and alarm in
     * sync via the safety mode toggle, so dropping the alarm parameter here
     * just means the wheel keeps whatever alarm value it had configured.
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray {
        if (useP6Protocol) return InMotionV2Commands.setP6MaxSpeed(tiltbackKmh)
        val m = detectedModel
        return if (m == null || m.maxSpeedHasAlarms) {
            InMotionV2Commands.setMaxSpeedV14(tiltbackKmh, alarmKmh)
        } else {
            InMotionV2LegacyCommands.setMaxSpeedShort(tiltbackKmh)
        }
    }

    /**
     * No flash-commit needed for P6 max-speed. Earlier builds sent a
     * `60 3e [tilt 00 00]` write here, believing it was a "commit max-speed
     * to flash" packet — re-analysis of the labelled max-speed-drag capture
     * (`docs/P6_CAPTURE_LABELS.md`) showed that opcode is the **alarm-speed**
     * setter; the InMotion app fires it with the new max-speed value only
     * to clamp `alarm ≤ max` after a downward drag. `60 21 [tilt]` alone
     * is sufficient and persists across reboots — multiple mid-drag `60 21`
     * writes without a `60 3e` follow-up were honoured by the wheel and
     * stayed put after power-cycle.
     *
     * Sending the redundant `60 3e [tilt]` was overwriting alarm with
     * tiltback transiently before the proper alarm write landed, which on
     * the user's hardware presented as "wheel bugs out when changing speed".
     */
    override fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? = null

    override fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? =
        if (useP6Protocol) InMotionV2Commands.setP6AlarmSpeed(alarmKmh) else null

    override fun setVolume(percent: Int): ByteArray = InMotionV2Commands.setVolume(percent)
    override fun setDRL(on: Boolean): ByteArray = InMotionV2Commands.setDRL(on)
    override fun setLock(locked: Boolean): ByteArray = InMotionV2Commands.setLock(locked)

    override fun requestAuthKey(): ByteArray = InMotionV2Commands.requestAuthKey()
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray =
        InMotionV2Commands.verifyAuth(encryptedKey)

    /**
     * The InMotion P6 silently drops control commands (light, horn, auto
     * headlight, max-speed) until the password auth handshake has run
     * once after connect. The handshake is a fixed echo (the wheel returns
     * a 16-byte "encrypted" blob and accepts the same blob back), so
     * running it adds no security but unlocks the control endpoint.
     *
     * V14 family wheels do NOT need this — their light/horn writes work
     * pre-auth; only lock requires the handshake on demand.
     */
    override fun requiresConnectAuth(): Boolean = useP6Protocol

    /**
     * Walk the reassembly buffer for complete AA AA frames, parse each, decode,
     * and return the resulting DecodeResults. Mirrors the legacy reassembly that
     * used to live in BleConnectionManager — preserved byte-for-byte to keep V14
     * behavior identical.
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        reassemblyBuffer.write(rawBytes)
        val buffer = reassemblyBuffer.toByteArray()
        val results = mutableListOf<DecodeResult>()

        var start = -1
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == InMotionV2Protocol.HEADER && buffer[i + 1] == InMotionV2Protocol.HEADER) {
                if (start >= 0) {
                    // Found next header — previous packet ends just before it
                    InMotionV2Protocol.parsePacket(buffer.copyOfRange(start, i))?.let {
                        results += decode(it.command, it.data)
                    }
                }
                start = i
            }
        }

        if (start >= 0) {
            val candidate = buffer.copyOfRange(start, buffer.size)
            if (candidate.size >= 5) {
                val packet = InMotionV2Protocol.parsePacket(candidate)
                if (packet != null) {
                    results += decode(packet.command, packet.data)
                    reassemblyBuffer.reset()
                    return results
                }
            }
            // Incomplete trailing packet — keep it in the buffer for the next notification
            reassemblyBuffer.reset()
            reassemblyBuffer.write(candidate)
        } else {
            // No header in the buffer at all — discard
            reassemblyBuffer.reset()
        }

        return results
    }

    override fun onDisconnect() {
        reassemblyBuffer.reset()
        detectedModel = null
        useP6Protocol = false
    }

    /** Internal decode of an unwrapped V2 packet. Called from [onRawNotification]. */
    private fun decode(command: Byte, data: ByteArray): DecodeResult {
        return when (command.toInt() and 0x7F) {
            0x02 -> decodeMainInfoOrAuth(data)
            0x04 -> parseTelemetryForModel(data)?.let { DecodeResult.Telemetry(it) } ?: DecodeResult.Unknown
            0x11 -> InMotionV2Parser.parseTotalStats(data)?.let { DecodeResult.TotalDistance(it.totalDistanceKm) } ?: DecodeResult.Unknown
            0x20 -> parseSettingsForModel(data)?.let { DecodeResult.Settings(it) } ?: DecodeResult.Unknown
            0x21 -> decodeP6Extended(data)
            else -> DecodeResult.Unknown
        }
    }

    /**
     * Unwrap a P6-style extended-routing response. The frame body looks like
     * `02 (sub|0x80) (01 00) (payload)` for sub 0x06 (info) and 0x07 (realtime),
     * and `02 (sub|0x80) (payload)` for sub 0x10 / 0x11 / 0x60 etc. We only
     * decode the two we trust today; the rest pass through silently.
     */
    private fun decodeP6Extended(data: ByteArray): DecodeResult {
        if (data.size < 3 || data[0] != 0x02.toByte()) return DecodeResult.Unknown
        val sub = data[1].toInt() and 0x7F
        return when (sub) {
            0x07 -> {
                // realtime: skip the `02 87 01 00` prefix to land on the data block
                if (data.size < 4) return DecodeResult.Unknown
                val telem = InMotionV2Parser.parseP6Telemetry(data.copyOfRange(4, data.size))
                telem?.let { DecodeResult.Telemetry(it) } ?: DecodeResult.Unknown
            }
            0x06 -> {
                // info bundle: skip `02 86 01 00`, then ASCII serial follows the
                // 0x01 record marker. We surface the serial as the model name so
                // the dashboard has *something* to identify the wheel until a
                // proper P6 parser lands.
                if (data.size < 4) return DecodeResult.Unknown
                val serial = InMotionV2Parser.parseP6Serial(data.copyOfRange(4, data.size))
                if (serial != null) {
                    DecodeResult.ModelName("InMotion P6 ($serial)", InMotionV2Model.P6)
                } else DecodeResult.Unknown
            }
            0x20 -> {
                // settings page A: `02 a0 [body]` — the body starts with a 0x20
                // sub-cmd echo and the parser pulls tiltback at offset 13-14.
                if (data.size < 3) return DecodeResult.Unknown
                val settings = InMotionV2Parser.parseP6Settings(data.copyOfRange(2, data.size))
                settings?.let { DecodeResult.Settings(it) } ?: DecodeResult.Unknown
            }
            else -> DecodeResult.Unknown
        }
    }

    /**
     * Telemetry parser dispatch. V12 HS/HT/Pro use a tighter byte layout than
     * V14; everything else (V14 family + unknown) falls through to the V14
     * parser, which is the verified default.
     */
    private fun parseTelemetryForModel(data: ByteArray) = when (detectedModel) {
        InMotionV2Model.V12HS, InMotionV2Model.V12HT, InMotionV2Model.V12PRO ->
            InMotionV2ParserV12.parseTelemetry(data)
        else -> InMotionV2Parser.parseTelemetry(data)
    }

    private fun parseSettingsForModel(data: ByteArray) = when (detectedModel) {
        InMotionV2Model.V12HS, InMotionV2Model.V12HT, InMotionV2Model.V12PRO ->
            InMotionV2ParserV12.parseSettings(data)
        else -> InMotionV2Parser.parseSettings(data)
    }

    /**
     * Command 0x02 carries both MainInfo subtypes (carType / firmware / serial)
     * and auth responses (routing 0x80). The first byte distinguishes them.
     */
    private fun decodeMainInfoOrAuth(data: ByteArray): DecodeResult {
        if (data.isEmpty()) return DecodeResult.Unknown
        return when (data[0].toInt() and 0xFF) {
            0x01 -> {
                // Car type
                val info = InMotionV2Parser.parseCarType(data.copyOfRange(1, data.size))
                if (info != null) {
                    detectedModel = info.model
                    DecodeResult.ModelName(info.modelName, info.model)
                } else DecodeResult.Unknown
            }
            0x06 -> {
                // Firmware versions
                val fw = InMotionV2Parser.parseVersions(data.copyOfRange(1, data.size))
                if (fw != null) DecodeResult.Firmware(
                    display = fw.displayString,
                    mainBoard = fw.mainBoardVersion,
                    driverBoard = fw.driverBoardVersion,
                    ble = fw.bleVersion
                ) else DecodeResult.Unknown
            }
            0x80 -> {
                // Auth response: data = [0x80, sub_cmd, payload...]
                if (data.size < 2) return DecodeResult.Unknown
                when (data[1].toInt() and 0xFF) {
                    0x02 -> {
                        // Auth key: 16 bytes encrypted password starting at data[2]
                        if (data.size >= 18) DecodeResult.AuthKey(data.copyOfRange(2, 18))
                        else DecodeResult.Unknown
                    }
                    0x82 -> {
                        // Auth verify result: data[2] == 0x01 means success
                        DecodeResult.AuthConfirm(data.size >= 3 && data[2].toInt() == 0x01)
                    }
                    else -> DecodeResult.Unknown
                }
            }
            else -> DecodeResult.Unknown
        }
    }
}
