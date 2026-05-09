package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * KingSong wheel adapter — stub. Recognises KS-* / S22 / S20 / S18 family
 * wheels on the HM-10 (0xFFE0 / 0xFFE1) BLE profile and routes their
 * telemetry through the same `WheelAdapter` interface that drives V14 and P6.
 *
 * Wire format (per docs/protocols/kingsong.md):
 *   - Fixed 20-byte frames `AA 55 ... type 14 5A 5A`, no CRC, mostly u16 LE.
 *   - Inbound packet types include live `0xA9`, trip `0xB9`, name `0xBB`,
 *     speed-limit `0xF6`, BMS pages `0xF1`/`0xF2`, etc.
 *   - Outbound commands: beep `0x88`, light `0x73`, pedal mode `0x87`,
 *     query `0x98`.
 *
 * This stub returns null/Unknown for every method. Telemetry parsing,
 * command building and the per-packet-type dispatcher will land in
 * follow-up commits — kept separate so reviewers can verify the
 * scaffolding (scan filter, model registry, capability gating) in
 * isolation before the wire-format work goes in.
 *
 * Protocol research credit: WheelLog (Ilya Shkolnik and contributors,
 * https://github.com/Wheellog/wheellog.android — GPLv3, used as a protocol
 * reference; the implementation here is original).
 */
@Singleton
class KingsongAdapter @Inject constructor() : WheelAdapter {
    override val familyId = "kingsong"
    override val capabilities = WheelCapabilities.KINGSONG

    @Volatile private var detectedModel: KingsongModel? = null

    override fun bleProfile(): BleProfile = BleProfile.HM10

    override fun notifyConnectingTo(deviceName: String?) {
        detectedModel = deviceName?.let { KingsongModel.fromReportedName(it) }
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
