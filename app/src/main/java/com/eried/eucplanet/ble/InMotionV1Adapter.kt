package com.eried.eucplanet.ble

import com.eried.eucplanet.diagnostics.DiagnosticCommand
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WheelAdapter for the InMotion V1 protocol family: V5 / V8 / V10 / L6 /
 * Glide 3 / Lively, plus the legacy R / V3 series. All share the byte-stuffed
 * `AA AA … 55 55` framing wrapped around a 16-byte CAN prefix; the wheel is
 * disambiguated post-connect by the model code in the slow-info reply
 * (offsets 104 / 107); see docs/protocols/inmotion_v1.md.
 *
 * V1 is on the proprietary 0xFFE4 / 0xFFE9 BLE profile (split across two
 * services), distinct from the KingSong / Begode / Veteran 0xFFE0 / 0xFFE1
 * profile and from the V2 Nordic UART profile.
 */
@Singleton
class InMotionV1Adapter @Inject constructor() : WheelAdapter {

    override val familyId = "inmotion_v1"
    override val familyDisplayName = "InMotion V1 / V3 / V5 / V8"
    override val capabilities = WheelCapabilities.INMOTION_V1

    companion object {
        /** Factory PIN InMotion V1 wheels ship with; sent on connect when no
         *  custom PIN is set. Wheels with no PIN configured ignore it. */
        private const val DEFAULT_PIN = "000000"
    }

    @Volatile private var detectedModel: InMotionV1Model? = null

    /**
     * 6-digit PIN for the V1 auth handshake (spec section 7). null means "use
     * the factory default" ([DEFAULT_PIN]); a future "Saved PINs" preference
     * can set a custom one here. The handshake is sent on every connect - the
     * wheel ignores it when no PIN is configured, so it is safe to always send.
     */
    @Volatile var pin: String? = null

    /**
     * Reassembly buffer for `AA AA … 55 55` frames split across BLE
     * notifications. V1 frames are larger than the typical 20-byte MTU
     * (slow-info replies run 132+ bytes), so reassembly is mandatory.
     */
    private val reassemblyBuffer = ByteArrayOutputStream()

    /** CAN IDs already logged as unhandled this connection, so an unexpected
     *  frame the wheel repeats at ~10 Hz is noted once, not thousands of times.
     *  Cleared on disconnect. */
    private val loggedUnknownCanIds = mutableSetOf<Int>()

    /** True once the wheel has sent a real fast/slow-info reply this connection,
     *  i.e. it has accepted the PIN handshake and is streaming. Until then the
     *  poll loop keeps re-sending the PIN. Reset on each connect / disconnect. */
    @Volatile private var streamStarted = false

    /** Poll counter used to alternate PIN and fast-info while not yet streaming. */
    @Volatile private var authPollTick = 0

    override fun bleProfile(): BleProfile = BleProfile.INMOTION_V1

    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        detectedModel = deviceName?.let { InMotionV1Model.fromReportedName(it) }
        streamStarted = false
        authPollTick = 0
        return null
    }

    /**
     * PIN handshake first, then slow-info. The V8S sits in an identity-only
     * state (broadcasting 0x0F060101, never fast-info) for ~30 s after power-on
     * until it receives the PIN; the app never sent one (the pin field was
     * always null), so it waited out the wheel's boot on every connect. Always
     * send the PIN now, defaulting to the factory 000000 - a wheel with no PIN
     * configured ignores it (spec section 7), so it is safe. Slow-info follows
     * so the model code + serial come back before realtime polling begins.
     */
    override fun initSequence(): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        out += InMotionV1Commands.sendPin(pin ?: DEFAULT_PIN)
        out += InMotionV1Commands.getSlowInfo()
        return out
    }

    /**
     * Realtime poll. Critically, keep RE-SENDING the PIN handshake until the
     * wheel actually starts streaming, not just once on connect. A single PIN
     * sent the instant we connect is ignored (the V8S isn't ready that early)
     * and the wheel then drops the link ~8 s later (status=8), so a single-shot
     * PIN only ever worked by luck after ~10 reconnect cycles. WheelLog re-sends
     * the password ~6x at 250 ms; do the same here so a later PIN lands once the
     * wheel is ready and it authenticates + streams on ONE connection. Alternate
     * PIN and fast-info so we both re-auth and poll; once a real reply arrives
     * ([streamStarted]) drop the PIN and just poll fast-info.
     */
    override fun pollRealtime(): ByteArray {
        if (!streamStarted) {
            authPollTick++
            if (authPollTick % 2 == 1) return InMotionV1Commands.sendPin(pin ?: DEFAULT_PIN)
        }
        return InMotionV1Commands.getFastInfo()
    }

    override fun pollSettings(): ByteArray = InMotionV1Commands.getSlowInfo()

    /**
     * Horn dispatch: V8F / V8S / V10 family / Glide 3 use the dedicated horn
     * opcode, everything else falls back to playSound(4) per spec table 8.
     * Unknown models default to the legacy playSound; the dedicated opcode
     * is silently ignored on wheels that don't support it.
     */
    override fun horn(): ByteArray {
        val m = detectedModel
        return if (m?.hasDedicatedHorn == true) {
            InMotionV1Commands.hornDedicated()
        } else {
            InMotionV1Commands.hornLegacy()
        }
    }

    override fun setLight(on: Boolean): ByteArray = InMotionV1Commands.setLight(on)

    /**
     * V1 max-speed packet only carries the tiltback threshold; alarm speed
     * is not a separate commandable setting, so [alarmKmh] is dropped. The
     * UI gates the alarm slider on [WheelCapabilities.hasAlarmSpeed], so
     * users won't see the field for V1 wheels.
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray =
        InMotionV1Commands.setMaxSpeed(tiltbackKmh)

    override fun setVolume(percent: Int): ByteArray? =
        if (detectedModel?.hasVolume == true) InMotionV1Commands.setVolume(percent) else null

    override fun setDRL(on: Boolean): ByteArray? =
        if (detectedModel?.hasDRL == true) InMotionV1Commands.setDRL(on) else null

    /** V1 has no remote lock command; lock state is read-only via work mode. */
    override fun setLock(locked: Boolean): ByteArray? = null

    /**
     * V1 PIN handshake is symmetric: the phone pushes the PIN, the wheel
     * acks with its own `0x0F550307` frame. There is no challenge / response
     * shaped like the V14 handshake, so [requestAuthKey] returns null and
     * the connection manager skips its V14-style two-step flow.
     */
    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * Reassemble notifications, scan for complete `AA AA … 55 55` frames,
     * unwrap each (escape reversal + checksum validation in
     * [InMotionV1Protocol.unwrap]) and dispatch by CAN ID. Incomplete trailing
     * bytes are kept in the buffer for the next notification.
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        reassemblyBuffer.write(rawBytes)
        val buffer = reassemblyBuffer.toByteArray()
        val results = mutableListOf<DecodeResult>()

        var consumed = 0
        var i = 0
        while (i < buffer.size - 1) {
            if (buffer[i] != InMotionV1Protocol.HEADER || buffer[i + 1] != InMotionV1Protocol.HEADER) {
                i++
                continue
            }
            val end = findFrameEnd(buffer, i + 2)
            if (end < 0) break // incomplete trailing frame, keep for next notification
            val frame = buffer.copyOfRange(i, end)
            InMotionV1Protocol.unwrap(frame)?.let { unwrapped ->
                results += dispatch(unwrapped)
            }
            consumed = end
            i = end
        }

        reassemblyBuffer.reset()
        if (consumed < buffer.size) reassemblyBuffer.write(buffer, consumed, buffer.size - consumed)
        return results
    }

    override fun onDisconnect() {
        reassemblyBuffer.reset()
        detectedModel = null
        loggedUnknownCanIds.clear()
        streamStarted = false
        authPollTick = 0
    }

    override fun inspectMessageTypes(): List<String> =
        listOf("InMotion V1 realtime", "InMotion V1 slow-info")

    /**
     * Service Mode catalogue for the InMotion V1 family. Each entry is a
     * single-shot CAN frame the user can fire from the Wheel Diagnostics
     * dialog to probe the wheel and watch the live log. The label is
     * bytes-derived so reports map back to one packet without ambiguity.
     */
    override fun getDiagnosticCommands(): List<DiagnosticCommand> {
        val QUERY = DiagnosticCommand.Category.QUERY
        val LIGHT = DiagnosticCommand.Category.LIGHT
        val HORN = DiagnosticCommand.Category.HORN
        val MODE = DiagnosticCommand.Category.MODE
        val OTHER = DiagnosticCommand.Category.OTHER

        return listOf(
            // --- Read-only queries ---
            DiagnosticCommand("Q0113", "Poll realtime fast-info reply",
                InMotionV1Commands.getFastInfo(), QUERY),
            DiagnosticCommand("Q0114", "Dump settings via slow-info",
                InMotionV1Commands.getSlowInfo(), QUERY),
            DiagnosticCommand("Q0114_CELLS", "Read battery cell levels",
                InMotionV1Commands.getBatteryCells(), QUERY),
            DiagnosticCommand("Q0114_FW", "Read firmware version block",
                InMotionV1Commands.getFirmwareVersion(), QUERY),

            // --- Lighting ---
            DiagnosticCommand("LIGHT_OFF", "Turn the headlight off",
                InMotionV1Commands.setLight(false), LIGHT),
            DiagnosticCommand("LIGHT_ON", "Turn the headlight on",
                InMotionV1Commands.setLight(true), LIGHT),

            // --- Horn ---
            DiagnosticCommand("HORN_DED", "Beep via dedicated V8F/V10 opcode",
                InMotionV1Commands.hornDedicated(), HORN),
            DiagnosticCommand("HORN_SND4", "Beep via legacy playSound 4",
                InMotionV1Commands.hornLegacy(), HORN),

            // --- Max speed writes ---
            DiagnosticCommand("MAXSPD_20", "Set tiltback to 20 km/h",
                InMotionV1Commands.setMaxSpeed(20f), MODE),
            DiagnosticCommand("MAXSPD_30", "Set tiltback to 30 km/h",
                InMotionV1Commands.setMaxSpeed(30f), MODE),

            // --- PIN auth probe ---
            // Wheels without a PIN configured ignore this, so it is safe to
            // fire; replies confirm the auth endpoint is alive.
            DiagnosticCommand("PIN_000000", "Send 000000 placeholder PIN",
                InMotionV1Commands.sendPin("000000"), OTHER)
        )
    }

    /**
     * Walk the buffer for the `55 55` trailer, skipping escape sequences so
     * an escaped `0xA5 0x55` byte inside the body isn't mistaken for the
     * trailer. Returns the exclusive end index of the frame (one past the
     * second `0x55`) or -1 when the trailer hasn't arrived yet.
     */
    private fun findFrameEnd(buffer: ByteArray, start: Int): Int {
        var i = start
        while (i < buffer.size - 1) {
            val b = buffer[i]
            if (b == InMotionV1Protocol.ESCAPE) {
                i += 2
                continue
            }
            if (b == InMotionV1Protocol.TRAILER && buffer[i + 1] == InMotionV1Protocol.TRAILER) {
                return i + 2
            }
            i++
        }
        return -1
    }

    private fun dispatch(unwrapped: ByteArray): List<DecodeResult> {
        val canId = InMotionV1Parser.canIdOf(unwrapped) ?: return emptyList()
        return when (canId) {
            InMotionV1Protocol.CanId.FAST_INFO -> {
                val payload = InMotionV1Parser.extPayload(unwrapped)
                DiagnosticsLogger.note(
                    "InMotion V1 realtime len=${payload.size} body=${payload.joinToString(" ") { "%02x".format(it) }}"
                )
                val telem = InMotionV1Parser.parseFastInfo(payload, detectedModel)
                // A real reply means the wheel accepted the PIN and is streaming;
                // stop the poll loop's PIN re-sends.
                if (telem != null) streamStarted = true
                if (telem != null) listOf(DecodeResult.Telemetry(telem)) else emptyList()
            }
            InMotionV1Protocol.CanId.SLOW_INFO -> {
                val payload = InMotionV1Parser.extPayload(unwrapped)
                DiagnosticsLogger.note(
                    "InMotion V1 slow-info len=${payload.size} body=${payload.joinToString(" ") { "%02x".format(it) }}"
                )
                val info = InMotionV1Parser.parseSlowInfo(payload) ?: return emptyList()
                streamStarted = true
                if (info.model != null) detectedModel = info.model
                val out = mutableListOf<DecodeResult>()
                out += DecodeResult.ModelName(
                    info.model?.displayName ?: "InMotion V1 (${info.serial})",
                    info.model
                )
                out += DecodeResult.Firmware(
                    display = "FW ${info.firmware}",
                    mainBoard = info.firmware,
                    driverBoard = "",
                    ble = ""
                )
                out += DecodeResult.Settings(info.settings)
                out
            }
            else -> {
                // Frame the wheel sends that isn't fast/slow-info. Normal
                // operation only exchanges the 0x0F55xxxx telemetry/command IDs
                // and 0x0F780101 alerts (docs/protocols/inmotion_v1.md 3.4), so
                // an unexpected ID is worth surfacing - a V8S that streams ONLY
                // 0x0F060101 and never fast-info, then drops with a link
                // supervision timeout, points at a wheel that won't leave its
                // standby / locked / not-riding state (or another BLE client
                // holding it). Log each distinct ID once per connection so the
                // diagnostics name it without flooding at the ~10 Hz it arrives.
                if (loggedUnknownCanIds.add(canId)) {
                    DiagnosticsLogger.note(
                        "InMotion V1 unhandled can=0x%08X len=${unwrapped.size} - wheel not streaming telemetry".format(canId)
                    )
                }
                emptyList()
            }
        }
    }
}
