package com.eried.eucplanet.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter dispatcher. Holds the six per-family adapters (InMotion V2, InMotion
 * V1, KingSong, Begode/Gotway, Veteran, Ninebot) and routes every WheelAdapter
 * call to the one that matches the connected wheel's BLE-advertised name.
 *
 * Selection happens on [notifyConnectingTo]: the BLE connection manager
 * already calls this hook before the first packet, so the rest of the
 * adapter surface (poll, decode, control commands) is delegated cleanly to
 * one sub-adapter from the first packet onward.
 *
 * If no name pattern matches we fall back to the InMotion V2 adapter (the
 * verified default). That keeps existing V14 / P6 setups working unchanged
 * when a wheel name doesn't match any other family pattern.
 */
@Singleton
class CompositeWheelAdapter @Inject constructor(
    private val inmotion: InMotionV2Adapter,
    private val inmotionV1: InMotionV1Adapter,
    private val kingsong: KingsongAdapter,
    private val begode: BegodeAdapter,
    private val veteran: VeteranAdapter,
    private val ninebot: NinebotAdapter
) : WheelAdapter {

    @Volatile private var active: WheelAdapter = inmotion

    /**
     * Every wheel-family adapter the Composite knows about, in the order
     * they should appear in the Service Mode wheel-family picker. Exposed
     * here so the diagnostics ViewModel can browse every family's command
     * catalogue / inspect prefixes without owning its own wiring.
     */
    val allFamilies: List<WheelAdapter> = listOf(
        inmotion, kingsong, veteran, begode, ninebot, inmotionV1
    )

    override val familyId: String get() = active.familyId
    override val capabilities: WheelCapabilities get() = active.capabilities

    override fun bleProfile(): BleProfile = active.bleProfile()

    override fun notifyConnectingTo(deviceName: String?): DecodeResult.ModelName? {
        active = pickAdapter(deviceName)
        return active.notifyConnectingTo(deviceName)
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
            // InMotion V1: V5 / V8 / V10 / L6 / Glide / Lively / IM<digits>.
            // V11/V12/V13/V14 are V2 family, route those to `inmotion`. The
            // disambiguation is by the *digit value* after the leading V:
            // 5/8/10 → V1, 11/12/13/14 → V2.
            isV1WheelName(n) -> inmotionV1

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
                    "lynx" in n -> veteran

            // Ninebot / Segway-Ninebot. Two protocol families live behind
            // the same brand prefix; the Ninebot adapter resolves Z vs
            // legacy from the same name string in its own
            // `notifyConnectingTo`. Route generously; if a name shape
            // overlaps with a KingSong "S2" prefix the KingSong branch
            // above already wins (it's more specific), so this branch
            // only sees real Ninebot names.
            n.startsWith("ninebot") || n.startsWith("segway") ||
                    Regex("^zn\\d").containsMatchIn(n) ||
                    n.startsWith("miniplus") || n.startsWith("mini plus") -> ninebot

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

    /**
     * Decide whether a name belongs to the V1 protocol family. V1 wheels are
     * V3 / V5 / V8 / V10 / L6 / Glide / Lively / R-series / "IM<digits>"; V2
     * wheels (V11 / V12 / V13 / V14) share the same `V<digits>` shape. We
     * disambiguate by parsing the digit run after the leading `V` and routing
     * V3 / V5 / V8 / V10 (with any letter suffix) to V1, leaving V11+ on V2.
     */
    private fun isV1WheelName(n: String): Boolean {
        if (n.startsWith("l6") || n.startsWith("lively") || n.startsWith("glide") ||
            n.startsWith("solowheel")) return true
        if (n.startsWith("im")) {
            // IM<digits> naming used by R-series + rebrands, V1 family.
            return n.length > 2 && n[2].isDigit()
        }
        // "inmotion-v8", "inmotion-v10f", etc.
        val stripped = if (n.startsWith("inmotion-")) n.substring("inmotion-".length) else n
        if (stripped.length >= 2 && stripped[0] == 'v' && stripped[1].isDigit()) {
            var i = 1
            while (i < stripped.length && stripped[i].isDigit()) i++
            val digits = stripped.substring(1, i).toIntOrNull() ?: return false
            return digits == 3 || digits == 5 || digits == 8 || digits == 10
        }
        return false
    }
}
