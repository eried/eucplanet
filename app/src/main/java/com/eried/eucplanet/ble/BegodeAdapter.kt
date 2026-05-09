package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Begode/Gotway wheel adapter — stub. Recognises Master / RS / EX / T4 / MSP
 * / Hero / Mten / MSX / MCM5 family wheels on the HM-10 (0xFFE0 / 0xFFE1) BLE
 * profile.
 *
 * Wire format (per docs/protocols/begode.md):
 *   - 24-byte BIG-ENDIAN frames `55 AA <16-byte payload> <tag> <subidx> 5A 5A 5A 5A`.
 *   - Tag at offset 18 disambiguates `0x00` Live A, `0x01..0x03` BMS, `0x04`
 *     Live B, `0x07` extras.
 *   - Voltage scaling depends on per-model nominal voltage class (84 / 100 /
 *     126 / 134 / 151 V), see [BegodeModel.nominalVoltage].
 *
 * Outbound commands are single ASCII characters: `b` beep, `l`/`L` light
 * cycle, `>`/`=`/`<` tiltback level, `m`/`g` pedal mode, plus `W`-prefix
 * sub-menus for max-speed / volume / LED.
 *
 * This stub returns null/Unknown for every method. Realtime parsing,
 * the multi-tag dispatcher and the per-model voltage scaler land in
 * follow-up commits.
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

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?) {
        detectedModel = deviceName?.let { BegodeModel.fromReportedName(it) }
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
