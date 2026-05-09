package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Veteran wheel adapter — stub. Recognises Sherman / Patton / Lynx / Abrams
 * family wheels on the HM-10 (0xFFE0 / 0xFFE1) BLE profile, distinguished
 * from Begode by the `DC 5A 5C 20` magic-byte prefix on the first realtime
 * frame.
 *
 * Wire format (per docs/protocols/veteran.md):
 *   - Magic `DC 5A 5C 20`, length byte at offset 4, payload of LEN-1 bytes,
 *     optional CRC32-BE only when LEN > 38 (smart-BMS frames).
 *   - Distances are word-swapped u32 in meters (the unusual quirk).
 *   - Speed is i16 signed in 0.1 km/h; reverse motion produces small negatives.
 *   - Long frames (model >= 5 firmware) carry per-cell BMS data with a
 *     `pnum` slice tag at offset 46.
 *
 * Outbound commands are limited compared to KingSong/Begode: `b` (horn),
 * `SetLightON/OFF`, `SETh/m/s` (set speed thresholds), `CLEARMETER`. No
 * software lock, no volume.
 *
 * This stub returns null/Unknown for every method. Frame reassembly,
 * realtime parser and the BMS branch land in follow-up commits.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class VeteranAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "veteran"
    override val capabilities = WheelCapabilities.VETERAN

    @Volatile private var detectedModel: VeteranModel? = null

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?) {
        detectedModel = deviceName?.let { VeteranModel.fromReportedName(it) }
    }

    override fun initSequence(): List<ByteArray> = emptyList()
    override fun pollRealtime(): ByteArray = ByteArray(0)
    override fun pollSettings(): ByteArray = ByteArray(0)

    override fun horn(): ByteArray? = null
    override fun setLight(on: Boolean): ByteArray? = null
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? = null
    override fun setVolume(percent: Int): ByteArray? = null
    override fun setDRL(on: Boolean): ByteArray? = null
    override fun setLock(locked: Boolean): ByteArray? = null

    override fun requestAuthKey(): ByteArray? = null
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = null

    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> = emptyList()

    override fun onDisconnect() {
        detectedModel = null
    }
}
