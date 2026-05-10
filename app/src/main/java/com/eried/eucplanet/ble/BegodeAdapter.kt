package com.eried.eucplanet.ble

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
    override val capabilities = WheelCapabilities.BEGODE

    @Volatile private var detectedModel: BegodeModel? = null

    /**
     * Tracks the wheel's current light state across off/on/strobe so the
     * boolean [setLight] toggle can rotate predictably. Begode is the only
     * brand with a 3-state light (spec 6.5); we collapse strobe to "on" for
     * the on/off API and skip the strobe state in the cycle.
     */
    @Volatile private var lightOn: Boolean = false

    private val parser = BegodeParser()

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?) {
        detectedModel = deviceName?.let { BegodeModel.fromReportedName(it) }
    }

    // Begode wheels stream telemetry unsolicited — no init handshake, no poll
    // loop. Spec 5: settings arrive embedded in 0x04 frames at all times.
    override fun initSequence(): List<ByteArray> = emptyList()
    override fun pollRealtime(): ByteArray = ByteArray(0)
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
        return parser.feed(rawBytes, detectedModel)
    }

    override fun onDisconnect() {
        parser.reset()
        detectedModel = null
        lightOn = false
    }
}
