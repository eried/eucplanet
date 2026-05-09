package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter dispatcher. Holds the four per-family adapters (InMotion V2,
 * KingSong, Begode/Gotway, Veteran) and routes every WheelAdapter call to
 * the one that matches the connected wheel's BLE-advertised name.
 *
 * Selection happens on [notifyConnectingTo] — the BLE connection manager
 * already calls this hook before the first packet, so the rest of the
 * adapter surface (poll, decode, control commands) is delegated cleanly to
 * one sub-adapter from the first packet onward.
 *
 * If no name pattern matches we fall back to the InMotion V2 adapter (the
 * verified default). That keeps existing V14 / P6 setups working unchanged
 * — a wheel that isn't a KS / Begode / Veteran name pattern matches the
 * "Adventure-*", "P6-*", or generic "InMotion*" patterns the V2 adapter
 * already understands.
 */
@Singleton
class CompositeWheelAdapter @Inject constructor(
    private val inmotion: InMotionV2Adapter,
    private val kingsong: KingsongAdapter,
    private val begode: BegodeAdapter,
    private val veteran: VeteranAdapter
) : WheelAdapter {

    @Volatile private var active: WheelAdapter = inmotion

    override val familyId: String get() = active.familyId
    override val capabilities: WheelCapabilities get() = active.capabilities

    override fun bleProfile(): BleProfile = active.bleProfile()

    override fun notifyConnectingTo(deviceName: String?) {
        active = pickAdapter(deviceName)
        active.notifyConnectingTo(deviceName)
    }

    override fun initSequence(): List<ByteArray> = active.initSequence()
    override fun pollRealtime(): ByteArray = active.pollRealtime()
    override fun pollSettings(): ByteArray = active.pollSettings()

    override fun horn(): ByteArray? = active.horn()
    override fun setLight(on: Boolean): ByteArray? = active.setLight(on)
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? =
        active.setMaxSpeed(tiltbackKmh, alarmKmh)
    override fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? =
        active.setMaxSpeedCommit(tiltbackKmh)
    override fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? =
        active.setAlarmSpeedCommit(alarmKmh)

    override fun setVolume(percent: Int): ByteArray? = active.setVolume(percent)
    override fun setDRL(on: Boolean): ByteArray? = active.setDRL(on)
    override fun setLock(locked: Boolean): ByteArray? = active.setLock(locked)

    override fun requestAuthKey(): ByteArray? = active.requestAuthKey()
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = active.verifyAuth(encryptedKey)

    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> =
        active.onRawNotification(rawBytes)

    override fun onDisconnect() {
        active.onDisconnect()
        // Reset the dispatch back to the verified default so the next
        // connect attempt starts from a clean state if the user picks a
        // different wheel.
        active = inmotion
    }

    private fun pickAdapter(deviceName: String?): WheelAdapter {
        if (deviceName.isNullOrBlank()) return inmotion
        val n = deviceName.lowercase()
        return when {
            // KingSong: "KS-…", "S22 …", "S20 …", etc.
            n.startsWith("ks-") || n.startsWith("ks ") ||
                    n.startsWith("kingsong") ||
                    Regex("^s(?:1[6-9]|2[02])(?:\\b|[-_ ])").containsMatchIn(n) ||
                    n.startsWith("f18") || n.startsWith("f22") -> kingsong

            // Veteran: explicit names. Veteran wheels sometimes also
            // advertise as "GotWay_*" with the same firmware family;
            // when that happens we'll catch them post-connect by
            // sniffing the `DC 5A 5C` magic in the future router. For
            // the BLE-name pre-select we only route Veteran when the
            // model name is unambiguous.
            "sherman" in n || "patton" in n || "abrams" in n ||
                    Regex("\\blynx\\b").containsMatchIn(n) -> veteran

            // Begode/Gotway. "GotWay_*" / "Begode_*" / model-specific
            // prefixes ("RS_*", "Master_*", "EX_*", "MSP_*", etc.).
            n.startsWith("gotway") || n.startsWith("begode") ||
                    n.startsWith("master") || n.startsWith("rs_") || n.startsWith("rs-") ||
                    n.startsWith("ex_") || n.startsWith("ex.") || n.startsWith("ex2") ||
                    n.startsWith("msp") || n.startsWith("msx") ||
                    n.startsWith("mten") || n.startsWith("mcm5") ||
                    n.startsWith("hero") || n.startsWith("t3") || n.startsWith("t4") -> begode

            // InMotion V2 default (V14 "Adventure-*", P6 "P6-*", and
            // every "InMotion*" / "V*" name handled inside that adapter).
            else -> inmotion
        }
    }
}
