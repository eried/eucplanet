package com.eried.eucplanet.ble

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
 * sets thresholds, not absolute max — see spec section 6 "Notes").
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
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
    // enabled — no init query, no realtime poll, no settings poll. Settings
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

    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    /**
     * Reassemble the byte stream into Veteran frames and dispatch each to
     * the right parser. Short frames produce a [DecodeResult.Telemetry];
     * long (smart-BMS) frames are parsed for cell/temp data but currently
     * surfaced as Unknown — there's no DecodeResult shape for per-cell
     * voltages yet, so we'd be losing fidelity by squeezing them through
     * the existing telemetry record. They'll get their own result type
     * once the dashboard grows a BMS panel.
     */
    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        val frames = parser.feed(rawBytes)
        if (frames.isEmpty()) return emptyList()
        val out = mutableListOf<DecodeResult>()
        for (f in frames) {
            if (f.isLong) {
                // Best-effort BMS parse so a malformed slice can't crash the
                // pipeline; result is discarded until the UI is ready for it.
                VeteranParser.parseLongFrame(f.bytes)
            } else {
                val telem = VeteranParser.parseTelemetry(f.bytes, detectedModel)
                if (telem != null) out += DecodeResult.Telemetry(telem)
            }
        }
        return out
    }

    override fun onDisconnect() {
        parser.reset()
        detectedModel = null
    }
}
