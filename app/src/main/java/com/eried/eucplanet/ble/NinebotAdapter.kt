package com.eried.eucplanet.ble

import android.util.Log
import com.eried.eucplanet.data.model.WheelSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ninebot / Segway-Ninebot wheel adapter. Two protocol families live behind
 * the same brand:
 *   - Ninebot Z (Z6, Z10, new-stack E+, Mini Plus): Nordic-UART GATT,
 *     XOR-encrypted `5A A5` framing, full settings surface.
 *   - Ninebot legacy (One E, One E+, S2, Mini, Mini Pro): HM-10 GATT,
 *     plaintext `55 AA` framing, read-only telemetry.
 *
 * The protocol is selected from the BLE-advertised name in [notifyConnectingTo]
 * and used to pick the right [BleProfile] before connect. After connect, Z
 * runs a GetKey handshake (cmd 0x5B to address 0x16); the 16-byte reply
 * installs the keystream in [crypto] and every subsequent frame is XOR'd in
 * place. Legacy needs no handshake — it streams unsolicited live-data once
 * notifications are enabled.
 *
 * Wire format and command set come from docs/protocols/ninebot.md. Protocol
 * research credit: WheelLog (Ilya Shkolnik / Palachzzz and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class NinebotAdapter @Inject constructor() : WheelAdapter {

    /**
     * Detected model. Carries the protocol family — the rest of the adapter
     * branches on `detectedModel?.protocol` to dispatch Z vs legacy. Set
     * from the BLE name in [notifyConnectingTo] and re-confirmed if the
     * legacy BleVersion ASCII tag identifies a different variant.
     */
    @Volatile private var detectedModel: NinebotModel? = null

    /**
     * Active protocol family. Defaults to Z so the BLE profile getter has
     * something to return when the dispatcher asks before [notifyConnectingTo]
     * fires. The dispatcher only routes a wheel to this adapter when its
     * name matches a Ninebot pattern, so the pre-name default rarely matters
     * in practice.
     */
    @Volatile private var activeProtocol: NinebotProtocol = NinebotProtocol.Z

    private val crypto = NinebotZCrypto()

    /**
     * Frame reassembler. Re-created on each protocol switch (notifyConnectingTo)
     * so the buffer never carries Z bytes into a legacy session or vice versa.
     */
    @Volatile private var parser: NinebotParser = NinebotParser(NinebotProtocol.Z)

    /**
     * Per-connection rolling settings snapshot. Z replies one param at a time;
     * we merge each parsed param into this and emit the merged record so the
     * UI only sees one consistent settings object instead of partial updates.
     */
    @Volatile private var settingsSnapshot: WheelSettings = WheelSettings()

    /**
     * Last seen value of the DriveFlags bitfield (param 0xD3). Bit 0 is DRL,
     * bit 1 is tail-light, bit 2 is headlight. We cache the whole byte so
     * [setLight] / [setDRL] can read-modify-write a single bit without
     * clobbering the others (a fresh-factory wheel happens to have most
     * bits zero, but a rider who's enabled DRL via the official app would
     * otherwise see DRL turn off the first time they touch the in-app
     * headlight toggle).
     */
    @Volatile private var lastDriveFlags: Int = 0

    /**
     * Round-robin pointer into [Z_SETTINGS_QUERIES]. Settings poll cycles
     * through one read per tick so the wheel isn't asked for everything on
     * every poll.
     */
    @Volatile private var settingsCursor: Int = 0

    override val familyId = "ninebot"
    override val familyDisplayName = "Ninebot"
    override val capabilities: WheelCapabilities
        get() = if (activeProtocol == NinebotProtocol.Z)
            WheelCapabilities.NINEBOT_Z
        else
            WheelCapabilities.NINEBOT_LEGACY

    override fun bleProfile(): BleProfile = when (activeProtocol) {
        NinebotProtocol.Z      -> BleProfile.NORDIC_UART
        NinebotProtocol.LEGACY -> BleProfile.HM10
    }

    /**
     * Resolve the model + protocol family from the BLE-advertised name. We
     * default to Z when the name is unrecognised but the dispatcher routed
     * the connection here anyway — anything advertising as `Ninebot ...`
     * without an obvious One/S2/Mini token is most likely a Z variant. If
     * the name signals legacy explicitly we flip the protocol BEFORE the
     * connection manager reads [bleProfile].
     */
    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        val resolved = deviceName?.let { NinebotModel.fromReportedName(it) }
        detectedModel = resolved
        activeProtocol = resolved?.protocol ?: NinebotProtocol.Z
        parser = NinebotParser(activeProtocol)
        crypto.clearKey()
        settingsSnapshot = WheelSettings()
        settingsCursor = 0
        return null
    }

    /**
     * Z: kick off the GetKey handshake first so every other frame after this
     * one can ride the encrypted channel. The wheel replies with the
     * 16-byte session key in plaintext (it has to — the phone doesn't have
     * the key yet to decrypt anything else). After the key arrives the
     * parser's CRC step on subsequent reads will pass against the decrypted
     * copy and the adapter can move on to telemetry.
     *
     * Legacy: nothing to do; the wheel streams telemetry unsolicited once
     * notifications are on.
     */
    override fun initSequence(): List<ByteArray> = when (activeProtocol) {
        NinebotProtocol.Z -> listOf(
            // GetKey is the very first frame; it MUST go in plaintext (no
            // crypto yet). The CRC inside is plaintext too, which is what
            // the wheel expects for this single bootstrap frame.
            NinebotCommands.getKey(),
            // BleVersion read confirms the wheel speaks Z; serial + firmware
            // populate the identity card. These are encrypted-on-send by the
            // crypto helper as soon as the GetKey reply lands.
            encryptForSend(NinebotCommands.getBleVersion()),
            encryptForSend(NinebotCommands.getSerialNumber()),
            encryptForSend(NinebotCommands.getFirmware())
        )
        NinebotProtocol.LEGACY -> emptyList()
    }

    override fun pollRealtime(): ByteArray = when (activeProtocol) {
        NinebotProtocol.Z -> encryptForSend(NinebotCommands.getLiveData())
        NinebotProtocol.LEGACY -> ByteArray(0)
    }

    /**
     * Settings poll. Z fans out one read per tick across the parameter set
     * the UI cares about (lock, speed limit, alarms, volume, drive flags).
     * Legacy has no documented settings writes, so we don't read any either.
     */
    override fun pollSettings(): ByteArray = when (activeProtocol) {
        NinebotProtocol.Z -> {
            val builder = Z_SETTINGS_QUERIES[settingsCursor]
            settingsCursor = (settingsCursor + 1) % Z_SETTINGS_QUERIES.size
            encryptForSend(builder())
        }
        NinebotProtocol.LEGACY -> ByteArray(0)
    }

    // ---- Control surface (Z only; legacy returns null) ----------------------

    override fun horn(): ByteArray? = null // Z has no documented horn opcode (spec section 19).

    override fun setLight(on: Boolean): ByteArray? = when (activeProtocol) {
        // The Z headlight is bit 2 of DriveFlags (param 0xD3), NOT the LED
        // preset (0xC6). We previously wrote `setLedMode(1)` here which
        // toggles the rainbow light-show pattern, not the actual headlight.
        // Read-modify-write so DRL (bit 0) and tail-light (bit 1) stay put.
        NinebotProtocol.Z -> {
            val flags = if (on) lastDriveFlags or 0x04 else lastDriveFlags and 0x04.inv()
            encryptForSend(NinebotCommands.setDriveFlags(flags))
        }
        NinebotProtocol.LEGACY -> null
    }

    /**
     * Z encodes max-speed and alarm in separate params. The shared API only
     * gives us one tiltback + one alarm slot, so we map alarm -> alarm 1
     * and leave alarms 2..3 untouched (the user can twist them later via a
     * dedicated alarms screen). Returns the tiltback packet; the alarm
     * follow-up rides on [setMaxSpeedCommit] so the connection manager
     * paces them on consecutive ticks rather than one big write burst.
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = when (activeProtocol) {
        NinebotProtocol.Z -> encryptForSend(NinebotCommands.setSpeedLimit(tiltbackKmh))
        NinebotProtocol.LEGACY -> null
    }

    override fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? = when (activeProtocol) {
        // Make sure the speed-limit feature is enabled when the user moves
        // the slider; some Z firmwares ship with `LimitedMode = false` and
        // ignore writes to 0x74 until 0x72 flips on.
        NinebotProtocol.Z -> encryptForSend(NinebotCommands.setLimitedMode(true))
        NinebotProtocol.LEGACY -> null
    }

    override fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? = when (activeProtocol) {
        NinebotProtocol.Z -> encryptForSend(NinebotCommands.setAlarm1Speed(alarmKmh))
        NinebotProtocol.LEGACY -> null
    }

    /**
     * Volume slider is 0..100 in the UI; Ninebot Z accepts a 0..7 step.
     * Map linearly and rely on the command builder to coerce.
     */
    override fun setVolume(percent: Int): ByteArray? = when (activeProtocol) {
        NinebotProtocol.Z -> {
            val step = ((percent.coerceIn(0, 100) * 7) + 50) / 100
            encryptForSend(NinebotCommands.setVolume(step))
        }
        NinebotProtocol.LEGACY -> null
    }

    override fun setDRL(on: Boolean): ByteArray? = when (activeProtocol) {
        // Bit 0 of DriveFlags is DRL. Read-modify-write so headlight (bit 2)
        // and tail-light (bit 1) stay put.
        NinebotProtocol.Z -> {
            val flags = if (on) lastDriveFlags or 0x01 else lastDriveFlags and 0x01.inv()
            encryptForSend(NinebotCommands.setDriveFlags(flags))
        }
        NinebotProtocol.LEGACY -> null
    }

    override fun setLock(locked: Boolean): ByteArray? = when (activeProtocol) {
        NinebotProtocol.Z -> encryptForSend(NinebotCommands.setLock(locked))
        NinebotProtocol.LEGACY -> null
    }

    // Z encryption is wheel-side-driven (the wheel issues the key); there's
    // no per-session app-driven auth handshake like InMotion V14's password.
    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * Run the encryption step on a freshly-built command. When the key has
     * been installed (every frame after the GetKey handshake) we XOR in
     * place; before that, plaintext is correct. Frames produced by
     * [NinebotCommands] always carry plaintext + plaintext CRC, so this is
     * the single place encryption lands and there's no chance of double
     * XOR'ing or computing CRC on already-XOR'd bytes.
     */
    private fun encryptForSend(plaintextFrame: ByteArray): ByteArray {
        val out = plaintextFrame.copyOf()
        crypto.applyInPlace(out)
        return out
    }

    /**
     * Buffer raw notification bytes, walk the buffer for complete frames,
     * verify CRC (decrypting first if a Z key is installed), and dispatch
     * each parsed frame to [onZFrame] or [onLegacyFrame].
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        val frames = parser.feed(rawBytes, if (activeProtocol == NinebotProtocol.Z) crypto else null)
        if (frames.isEmpty()) return emptyList()

        val results = mutableListOf<DecodeResult>()
        for (frame in frames) {
            when (activeProtocol) {
                NinebotProtocol.Z      -> results += onZFrame(frame)
                NinebotProtocol.LEGACY -> results += onLegacyFrame(frame)
            }
        }
        return results
    }

    private fun onZFrame(frame: NinebotParser.Frame): List<DecodeResult> {
        // GetKey reply: plaintext, src = 0x16 (KeyGenerator). Install the
        // 16-byte data payload as the session key; subsequent frames are
        // expected encrypted from here on.
        if (frame.cmd == NinebotCommands.Cmd.GET_KEY && frame.src == NinebotCommands.ADDR_KEY_GENERATOR) {
            if (frame.data.size == NinebotZCrypto.KEY_LENGTH) {
                crypto.setKey(frame.data)
            } else {
                Log.w(TAG, "Ninebot Z GetKey reply had unexpected length: ${frame.data.size}")
            }
            return emptyList()
        }

        return when (frame.param) {
            NinebotCommands.Param.LIVE_DATA -> {
                // Surface the decrypted live-data body so the Service Mode
                // Inspect tab can rummage through offsets the parser doesn't
                // yet touch (status word, alarms, BMS hand-off bytes). Format
                // matches the V14 / P6 NOTE entries the same picker reads.
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                    "Ninebot realtime (Z) len=${frame.data.size} body=${frame.data.joinToString(" ") { "%02x".format(it) }}"
                )
                val telem = parser.parseZTelemetry(frame.data, detectedModel)
                if (telem != null) listOf(DecodeResult.Telemetry(telem)) else emptyList()
            }
            NinebotCommands.Param.SERIAL_NUMBER -> {
                val serial = parser.parseZSerial(frame.data) ?: return emptyList()
                val model = detectedModel
                listOf(DecodeResult.ModelName(model?.displayName ?: serial, model))
            }
            NinebotCommands.Param.FIRMWARE -> {
                val fw = parser.parseZFirmware(frame.data) ?: return emptyList()
                listOf(
                    DecodeResult.Firmware(
                        display = "FW $fw",
                        mainBoard = fw,
                        driverBoard = "",
                        ble = ""
                    )
                )
            }
            NinebotCommands.Param.LOCK_MODE -> {
                val v = parser.parseZLockState(frame.data) ?: return emptyList()
                settingsSnapshot = settingsSnapshot.copy(lockState = v)
                listOf(DecodeResult.Settings(settingsSnapshot))
            }
            NinebotCommands.Param.LIMIT_MODE_SPEED -> {
                val v = parser.parseZSpeedLimit(frame.data) ?: return emptyList()
                settingsSnapshot = settingsSnapshot.copy(maxSpeedKmh = v)
                listOf(DecodeResult.Settings(settingsSnapshot))
            }
            NinebotCommands.Param.ALARM1_SPEED -> {
                val v = parser.parseZAlarmSpeed(frame.data) ?: return emptyList()
                settingsSnapshot = settingsSnapshot.copy(alarmSpeedKmh = v)
                listOf(DecodeResult.Settings(settingsSnapshot))
            }
            NinebotCommands.Param.SPEAKER_VOLUME -> {
                // The shared WheelSettings record doesn't carry volume, but we
                // still parse the param so the snapshot stays self-consistent
                // and a future UI surfacing can read the raw byte without a
                // schema change here. Drop on the floor for now.
                emptyList()
            }
            NinebotCommands.Param.DRIVE_FLAGS -> {
                val flags = parser.parseZDriveFlags(frame.data) ?: return emptyList()
                // Cache the whole byte so subsequent setLight / setDRL writes
                // can read-modify-write a single bit without zeroing the rest.
                lastDriveFlags = flags
                val drl = (flags and 0x01) != 0
                settingsSnapshot = settingsSnapshot.copy(drl = drl)
                listOf(DecodeResult.Settings(settingsSnapshot))
            }
            else -> emptyList()
        }
    }

    private fun onLegacyFrame(frame: NinebotParser.Frame): List<DecodeResult> {
        return when (frame.param) {
            NinebotCommands.Param.LIVE_DATA -> {
                // Same NOTE prefix as the Z path so the Inspect tab can
                // subscribe to a single "Ninebot realtime" filter regardless
                // of which sub-protocol the connected wheel speaks.
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.note(
                    "Ninebot realtime (Legacy) len=${frame.data.size} body=${frame.data.joinToString(" ") { "%02x".format(it) }}"
                )
                val telem = parser.parseLegacyTelemetry(frame.data, detectedModel)
                if (telem != null) listOf(DecodeResult.Telemetry(telem)) else emptyList()
            }
            NinebotCommands.Param.BLE_VERSION -> {
                // Legacy reply selects the variant. WheelLog reads an ASCII
                // tag here ("S2" / "Mini" / empty for default). Re-resolve
                // the model so the parser uses the right speed offset.
                val tag = parser.parseLegacyBleVersionTag(frame.data) ?: return emptyList()
                val model = when (tag.uppercase()) {
                    "S2" -> NinebotModel.ONE_S2
                    "MINI" -> NinebotModel.MINI
                    else -> detectedModel ?: NinebotModel.ONE_E
                }
                detectedModel = model
                listOf(DecodeResult.ModelName(model.displayName, model))
            }
            NinebotCommands.Param.SERIAL_NUMBER -> {
                val serial = parser.parseLegacySerial(frame.data) ?: return emptyList()
                val model = detectedModel
                listOf(DecodeResult.ModelName(model?.displayName ?: serial, model))
            }
            else -> emptyList()
        }
    }

    override fun onDisconnect() {
        parser.reset()
        crypto.clearKey()
        detectedModel = null
        // Reset to Z so the next connect starts from the same default the
        // dispatcher's pre-select assumes; notifyConnectingTo will overwrite
        // before any frame is sent.
        activeProtocol = NinebotProtocol.Z
        parser = NinebotParser(NinebotProtocol.Z)
        settingsSnapshot = WheelSettings()
        settingsCursor = 0
    }

    /**
     * Service Mode hooks the Inspect tab onto NOTE entries whose text starts
     * with this prefix. Both sub-protocols use the same prefix so the picker
     * shows a single "Ninebot realtime" filter no matter which family the
     * connected wheel speaks; the body line itself tags `(Z)` or `(Legacy)`
     * so the user still knows which path it came from.
     */
    override fun inspectMessageTypes(): List<String> =
        listOf("Ninebot realtime")

    /**
     * Per-wheel diagnostic test commands shown in the Service Mode dialog.
     * Bytes are baked at app start, so any Z entry fires PLAINTEXT — fine
     * for the GetKey bootstrap and for any session where the user hasn't
     * yet completed the handshake. Once the keystream is installed, the
     * wheel expects encrypted frames and a plaintext diagnostic write will
     * fail CRC on the wheel side. The catalogue is research-grade: the
     * value comes from being able to inspect the reply traffic, not from
     * driving the wheel mid-session.
     *
     * Each entry annotates `(Z)` or `(Legacy)` in its description so the
     * user knows which sub-protocol it belongs to. Most entries are Z —
     * legacy exposes only reads on its BLE stack (spec section 16).
     */
    override fun getDiagnosticCommands(): List<com.eried.eucplanet.diagnostics.DiagnosticCommand> {
        val QUERY = com.eried.eucplanet.diagnostics.DiagnosticCommand.Category.QUERY
        val LIGHT = com.eried.eucplanet.diagnostics.DiagnosticCommand.Category.LIGHT
        val MODE  = com.eried.eucplanet.diagnostics.DiagnosticCommand.Category.MODE
        val OTHER = com.eried.eucplanet.diagnostics.DiagnosticCommand.Category.OTHER

        fun entry(
            label: String,
            desc: String,
            cat: com.eried.eucplanet.diagnostics.DiagnosticCommand.Category,
            bytes: ByteArray
        ) = com.eried.eucplanet.diagnostics.DiagnosticCommand(label, desc, bytes, cat)

        return listOf(
            // --- Z handshake + identity reads (plaintext on the wire) ---
            entry("Z_GETKEY",  "Run the GetKey handshake (Z)",                OTHER, NinebotCommands.getKey()),
            entry("Q_SERIAL",  "Read the ASCII serial number (Z)",            QUERY, NinebotCommands.getSerialNumber()),
            entry("Q_FW",      "Read the firmware version word (Z)",          QUERY, NinebotCommands.getFirmware()),

            // --- Z telemetry + settings reads ---
            entry("Q_LIVE",    "Poll one live-data frame (Z)",                QUERY, NinebotCommands.getLiveData()),
            entry("Q_LOCK",    "Read the lock state (Z)",                     QUERY, NinebotCommands.readLockState()),
            entry("Q_SPEED",   "Read the speed limit value (Z)",              QUERY, NinebotCommands.readSpeedLimit()),
            entry("Q_ALARMS",  "Read the armed-alarm bitfield (Z)",           QUERY, NinebotCommands.readAlarms()),
            entry("Q_DRIVE",   "Read the drive-flags bitfield (Z)",           QUERY, NinebotCommands.readDriveFlags()),

            // --- Z writes: light, lock, max-speed enable + value ---
            entry("W_LED_OFF", "Turn the LED off, mode 0 (Z)",                LIGHT, NinebotCommands.setLedMode(0)),
            entry("W_LED_ON",  "Turn the LED on, mode 1 (Z)",                 LIGHT, NinebotCommands.setLedMode(1)),
            entry("W_LOCK",    "Lock the wheel via param 0x70 (Z)",           MODE,  NinebotCommands.setLock(true)),
            entry("W_UNLOCK",  "Unlock the wheel via param 0x70 (Z)",         MODE,  NinebotCommands.setLock(false)),
            entry("W_MAX25",   "Set max speed to 25 km/h (Z)",                MODE,  NinebotCommands.setSpeedLimit(25f)),

            // --- Legacy reads (plaintext 55 AA framing, no sub-protocol) ---
            entry("L_BLEVER",  "Read the BleVersion variant tag (Legacy)",    QUERY, NinebotCommands.getLegacyBleVersion()),
            entry("L_LIVE",    "Poll one live-data frame (Legacy)",           QUERY, NinebotCommands.getLegacyLiveData())
        )
    }

    companion object {
        private const val TAG = "NinebotAdapter"

        /**
         * Round-robin queries used by [pollSettings]. Each tick advances the
         * cursor by one so the wheel is never overwhelmed; over five ticks
         * the snapshot refreshes fully.
         */
        private val Z_SETTINGS_QUERIES: List<() -> ByteArray> = listOf(
            NinebotCommands::readLockState,
            NinebotCommands::readSpeedLimit,
            NinebotCommands::readAlarms,
            NinebotCommands::readDriveFlags,
            NinebotCommands::readVolume
        )
    }
}
