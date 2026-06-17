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
 */
@Singleton
class VeteranAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "veteran"
    // Internal id stays "veteran" so existing stored profiles / custom
    // commands keep working; user-visible label is the actual brand.
    override val familyDisplayName = "LeaperKim"
    // Brand is model-aware so the NOSFET rebrands (Aero / Apex / Aeon),
    // which share the wire protocol but are a separate manufacturer, surface
    // their own brand on the dashboard, in Settings and in eucstats meta
    // instead of inheriting LeaperKim. Until a model is detected the family
    // default is used. BleConnectionManager re-reads `brand` on each
    // ModelName decode, so the change propagates without a reconnect.
    override val brand: String get() = detectedModel?.brandOverride ?: familyDisplayName
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

    /**
     * Current Lynx-class firmware only sounds the horn when the `LkAp` frame
     * from [horn] is followed by this `LdAp` companion. Sending the `LkAp`
     * half alone leaves the wheel silent (the frame is accepted but
     * produces no beep). Decoded from a LeaperKim-app btsnoop on a Lynx S
     * (mVer 9). [com.eried.eucplanet.data.repository.WheelRepository.sendHorn]
     * writes both, in order.
     */
    override fun hornFollowup(): ByteArray = VeteranCommands.hornCompanion()

    /**
     * Light state is never echoed in Veteran realtime frames (per
     * docs/protocols/veteran.md §6: "Light state has no readback.
     * Track it locally after each write."). We cache the last
     * commanded state here and stamp it onto every outgoing telemetry
     * in [onRawNotification] so the dashboard's local-tracked
     * [WheelRepository.toggleLight] doesn't see lightOn flip back
     * to the default `false` on the very next 5 Hz realtime frame
     * — which is exactly the bug the LK19486 rider hit: first toggle
     * sent SetLightON, parser-default false overwrote it ~200 ms
     * later, next toggle re-sent SetLightON instead of SetLightOFF.
     */
    @Volatile private var lastLightOn: Boolean = false

    // Last Oryx BMS state-of-charge read from a page-2 sub-frame (byte 50).
    // The wheel only sends page 2 ~1 frame in 9, so we cache it and stamp it
    // onto every telemetry; otherwise battery would flicker back to the
    // voltage-curve estimate on the other 8 pages. -1 until the first page-2.
    @Volatile private var lastOryxBatterySoc: Int = -1

    // Lock-frame anti-replay counter. The wheel's lock frame carries an
    // 8-bit sequence at payload offset 7 that increments on every write; a
    // stale value triggers the wheel to ignore the command. Btsnoop on a
    // Lynx S used 0x09 for the first lock and 0x0e for the first unlock, so
    // we emit `base + sequence` where base is 0x09 for lock and 0x0e for
    // unlock.
    //
    // **Counter persists across BLE disconnects** for the lifetime of the
    // process. Resetting on disconnect would risk a replay rejection the
    // first toggle after a reconnect if the wheel keeps its expected-next
    // counter across the BLE session (likely — these wheels keep most state
    // until power-off). The trade-off if the wheel DOES reset its
    // expectation per session is harmless: a higher-than-expected counter
    // still increases, so the wheel accepts it. The only failure mode is the
    // counter wrapping past 0xFF after 246 toggles, which would take a few
    // months of daily toggling and self-heals on the next app restart.
    private val lockSequence = java.util.concurrent.atomic.AtomicInteger(0)

    // Resolved model name emitted once per connection, so the dashboard and the
    // experimental-banner gate can tell e.g. an Oryx from a Sherman. Veteran
    // wheels often advertise a generic BLE name, so we resolve from the in-frame
    // mVer (offset 28) and fall back to the name-derived model.
    @Volatile private var emittedModel: Boolean = false

    override fun setLight(on: Boolean): ByteArray {
        lastLightOn = on
        // HIGH beam by default (LkAp frame; LdAp companion via [setLightFollowup]).
        return VeteranCommands.setHighBeam(on)
        // LOW beam (legacy ASCII, single frame). To switch the in-app light
        // toggle back to the low beam: comment the high-beam return above,
        // uncomment the line below, and make [setLightFollowup] return null.
        // return VeteranCommands.setLight(on)
    }

    /**
     * Second frame of the high-beam command (`LdAp`); the wheel ignores the
     * `LkAp` half from [setLight] on its own. Decoded from the same Lynx S
     * btsnoop as the horn. If you switch [setLight] back to the low beam,
     * change this to `null` (low beam is a single ASCII frame).
     */
    override fun setLightFollowup(on: Boolean): ByteArray =
        VeteranCommands.setHighBeamCompanion(on)

    // Veteran writes tilt-back and alarm thresholds as two separate frames
    // (different magic + sub-op per setting), so we leave the combined
    // setMaxSpeed null and route through setMaxSpeedCommit / setAlarmSpeedCommit
    // — the same flow P6 already uses for its two-packet flash-commit.
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = null

    override fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray =
        VeteranCommands.setTiltbackSpeed(tiltbackKmh.toInt())

    override fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray =
        VeteranCommands.setAlarmSpeed(alarmKmh.toInt())

    // No volume, no DRL on this family.
    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    // Software lock decoded from a Lynx S btsnoop, June 2026 (see
    // VeteranCommands.setLock). Wheel CRC-validates the frame, so older
    // models that don't recognise the opcode silently ignore it — safe to
    // expose on every Veteran-family wheel without per-model gating.
    // The lock frame carries a session-monotonic counter: lock starts at
    // 0x09 + n, unlock at 0x0e + n, where n is the sequence of setLock
    // calls in this BLE session (matches the btsnoop on the first toggle
    // of each direction).
    override fun setLock(locked: Boolean): ByteArray {
        val n = lockSequence.getAndIncrement()
        val base = if (locked) 0x09 else 0x0E
        return VeteranCommands.setLock(locked, base + n)
    }

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
            val telem = VeteranParser.parseTelemetry(f.bytes, detectedModel)
            val emitted = if (telem != null) {
                // An Oryx (mVer 8) page-2 frame carries the wheel's own BMS SoC
                // (parseTelemetry put it in batteryPercent); cache it and stamp
                // the cached value onto every frame so battery stays steady
                // between page-2 frames. Gated on mVer 8 so other Veterans, which
                // don't carry SoC at byte 50, keep their per-frame curve value.
                if (VeteranParser.mVerOf(f.bytes) == 8 &&
                    VeteranParser.pageId(f.bytes) == 2 &&
                    telem.batteryPercent in 0..100
                ) {
                    lastOryxBatterySoc = telem.batteryPercent
                }
                val battery = if (lastOryxBatterySoc in 0..100) lastOryxBatterySoc
                              else telem.batteryPercent
                telem.copy(lightOn = lastLightOn, batteryPercent = battery)
            } else null
            // Log the DECODED values (not just raw bytes) per frame so a
            // service-mode capture shows the speed/battery timeline directly -
            // i.e. whether telemetry updates smoothly or in random bursts.
            DiagnosticsLogger.note(
                "Veteran realtime spd=${emitted?.let { "%.1f".format(it.speed) } ?: "-"} " +
                    "bat=${emitted?.batteryPercent ?: "-"} pg=${VeteranParser.pageId(f.bytes)} " +
                    "len=${f.bytes.size} body=${f.bytes.joinToString(" ") { "%02x".format(it) }}"
            )
            if (emitted != null) out += DecodeResult.Telemetry(emitted)
            // Surface the resolved model once, so the UI and the experimental
            // banner can distinguish models within the Veteran family.
            if (!emittedModel) {
                val model = VeteranModel.fromMVer(VeteranParser.mVerOf(f.bytes)) ?: detectedModel
                if (model != null) {
                    out += DecodeResult.ModelName(model.displayName, model)
                    emittedModel = true
                }
            }
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
        lastOryxBatterySoc = -1
        emittedModel = false
        // NB: `lockSequence` is deliberately NOT reset here — see the field
        // declaration. Resetting on disconnect risks a replay rejection on
        // the first toggle after a reconnect.
        // A wheel reboot loses light state on the wheel side, so the rider's
        // most reliable mental model after a reconnect is "light is off until
        // I press the button again". Reset the cache to match.
        lastLightOn = false
    }
}
