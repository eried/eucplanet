package com.eried.eucplanet.ble

import com.eried.eucplanet.diagnostics.DiagnosticCommand
import com.eried.eucplanet.diagnostics.DiagnosticsLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Veteran wheel adapter. Recognises Sherman / Sherman S / Sherman Max /
 * Patton / Lynx / Abrams / Oryx (and the rebranded Nosfet wheels) on the
 * HM-10 (0xFFE0 / 0xFFE1) BLE profile. Veteran is distinguished from Begode
 * and KingSong on the same UUIDs by the `DC 5A 5C` magic-byte prefix on the
 * first realtime frame.
 *
 * Wire format (per docs/protocols/veteran.md):
 *   - Magic `DC 5A 5C`, LEN at offset 3, payload of LEN-1 bytes; optional
 *     CRC32 (BE) when LEN > 38 for smart-BMS frames.
 *   - Distances are word-swapped u32 in meters (the LeaperKim quirk).
 *   - Speed is i16 BE in 0.1 km/h; reverse motion produces small negatives.
 *   - Long frames carry per-cell BMS data with a `pnum` slice tag at off 46.
 *
 * Outbound commands are limited compared to KingSong/Begode: 14-byte horn
 * blob, `SetLightON/OFF`, `SETh/m/s` pedal stiffness, `CLEARMETER` trip
 * reset. No software lock, no volume, no max-speed write (the SET* family
 * sets thresholds, not absolute max; see spec section 6 "Notes").
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android, GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class VeteranAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "veteran"
    override val familyDisplayName = "Veteran"
    override val capabilities = WheelCapabilities.VETERAN

    @Volatile private var detectedModel: VeteranModel? = null

    private val parser = VeteranParser()

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        detectedModel = deviceName?.let { VeteranModel.fromReportedName(it) }
        return null
    }

    // Veteran streams unsolicited telemetry as soon as notifications are
    // enabled: no init query, no realtime poll, no settings poll. Settings
    // come piggybacked on the same realtime frame (offsets 24..27 / 30) so
    // the dashboard refreshes naturally.
    override fun initSequence(): List<ByteArray> = emptyList()
    override fun pollRealtime(): ByteArray = ByteArray(0)
    override fun pollSettings(): ByteArray = ByteArray(0)

    override fun horn(): ByteArray = VeteranCommands.horn()

    override fun setLight(on: Boolean): ByteArray = VeteranCommands.setLight(on)

    /**
     * Veteran has no documented write command for absolute max speed. The
     * `SETh/m/s` family sets pedal stiffness thresholds, not the absolute
     * speed limit, so we return null here and let the UI gray the action
     * out via [WheelCapabilities.hasMaxSpeed]. Threshold control will land
     * on its own knob in a follow-up once the UI separates the two.
     */
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = null

    // No volume, no DRL, no software lock per spec section 8.
    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null

    // CLEARMETER zeroes offset 8..11 (trip) on the next frame; see
    // VeteranCommands.resetTrip and spec section 6.
    override fun resetTripMeter(): ByteArray = VeteranCommands.resetTrip()

    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * Service Mode Inspect tab subscribes to NOTE entries that start with
     * this prefix. The adapter logs every reassembled short frame body under
     * "Veteran realtime" so the user can pick it from the type dropdown and
     * watch raw telemetry land in real time.
     */
    override fun inspectMessageTypes(): List<String> =
        listOf("Veteran realtime")

    /**
     * Per-wheel diagnostic commands surfaced in the Wheel Diagnostics dialog.
     * Veteran's command surface is unusually small (see docs/protocols/veteran.md
     * section 6): only horn, light, pedals stiffness and trip-reset have
     * publicly documented wire formats. There is no documented max-speed,
     * alarm-speed, lock or volume write, so those buttons are deliberately
     * absent; the spec calls out section 9 "Open questions" for capturing
     * the official app and decoding them. Adding speculative bytes here
     * would risk faulting the wheel.
     *
     * Both horn variants are exposed so a user with a pre-2020 Sherman can
     * verify which one their firmware obeys; the runtime [horn] default is
     * the v3 blob.
     */
    override fun getDiagnosticCommands(): List<DiagnosticCommand> {
        val LIGHT = DiagnosticCommand.Category.LIGHT
        val HORN = DiagnosticCommand.Category.HORN
        val MODE = DiagnosticCommand.Category.MODE
        val OTHER = DiagnosticCommand.Category.OTHER

        return listOf(
            // --- Light: ASCII writes, no readback ---
            DiagnosticCommand("LightON", "Turn the headlight on",
                VeteranCommands.setLight(true), LIGHT),
            DiagnosticCommand("LightOFF", "Turn the headlight off",
                VeteranCommands.setLight(false), LIGHT),

            // --- Horn: model-conditional. v3 is the default; legacy is a probe. ---
            DiagnosticCommand("HornV3", "Sound the horn on Sherman S and newer",
                VeteranCommands.horn(), HORN),
            DiagnosticCommand("HornLegacy_b", "Sound the horn on pre-2020 Sherman",
                VeteranCommands.hornLegacy(), HORN),

            // --- Pedals stiffness: writes echo at offset 30 of next frame ---
            DiagnosticCommand("SETh", "Set pedals to hard",
                VeteranCommands.setPedalsHard(), MODE),
            DiagnosticCommand("SETm", "Set pedals to medium",
                VeteranCommands.setPedalsMedium(), MODE),
            DiagnosticCommand("SETs", "Set pedals to soft",
                VeteranCommands.setPedalsSoft(), MODE),

            // --- Trip reset: zeroes offset 8..11 on the next frame ---
            DiagnosticCommand("CLEARMETER", "Reset the trip meter to zero",
                VeteranCommands.resetTrip(), OTHER),
        )
    }

    /**
     * Reassemble the byte stream into Veteran frames and dispatch each to
     * the right parser. Telemetry (voltage / speed / current / temp / trip)
     * lives in the first ~36 bytes of every frame regardless of LEN, so
     * parseTelemetry runs for both short and long frames. Long (smart-BMS)
     * frames additionally carry per-cell data after offset 46; the cell
     * slice is decoded but currently discarded until the dashboard grows
     * a BMS panel. Without this, BMS-equipped wheels (Lynx S, Patton with
     * smart BMS, Oryx) connect but the dashboard stays at zero because
     * they only ever emit long frames.
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        val frames = parser.feed(rawBytes)
        if (frames.isEmpty()) return emptyList()
        val out = mutableListOf<DecodeResult>()
        for (f in frames) {
            DiagnosticsLogger.note(
                "Veteran realtime len=${f.bytes.size} body=${f.bytes.joinToString(" ") { "%02x".format(it) }}"
            )
            val telem = VeteranParser.parseTelemetry(f.bytes, detectedModel)
            if (telem != null) out += DecodeResult.Telemetry(telem)
            if (f.isLong) {
                // Best-effort BMS parse so a malformed slice can't crash the
                // pipeline; result is discarded until the UI is ready for it.
                VeteranParser.parseLongFrame(f.bytes)
            }
        }
        return out
    }

    override fun onDisconnect() {
        parser.reset()
        detectedModel = null
    }
}
