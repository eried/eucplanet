package com.eried.eucplanet.ble

import kotlin.concurrent.Volatile

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
class CompositeWheelAdapter : WheelAdapter {

    // Stateless per-family adapters, constructed once. Previously @Inject'd
    // singletons; the Composite is the only consumer, so it owns them directly
    // now that the whole family lives in :shared (no Hilt in commonMain).
    private val inmotion = InMotionV2Adapter()
    private val inmotionV1 = InMotionV1Adapter()
    private val kingsong = KingsongAdapter()
    private val begode = BegodeAdapter()
    private val veteran = VeteranAdapter()
    private val ninebot = NinebotAdapter()

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

    /**
     * Post-connect adapter rescue. Called when the name-picked adapter's service
     * is NOT present on the wheel - which on the BLE-name routing happens when
     * the wheel advertises a name we don't recognise (e.g. an S18 advertising as
     * `RW`) and we fall through to the InMotion V2 default, then can't find the
     * Nordic UART service on the actual KingSong HM-10 module.
     *
     * Looks at the GATT-discovered service UUID set and re-routes [active] to
     * whichever family matches. Returns true if the new active adapter's service
     * is among [discoveredServiceUuids] (caller can proceed); false if the wheel
     * doesn't expose any service we know about.
     *
     * Priority order matches uniqueness: Nordic UART -> InMotion V2 (single
     * brand). InMotion V1 split-service (FFE0+FFE5) -> InMotion V1. Bare HM-10
     * (FFE0+FFE1) is ambiguous between KingSong / Begode / Veteran; we default
     * to KingSong because the failing case (`RW`-named S18, blank-named KS) is
     * the common one. Names like `Sherman` / `Begode_*` always match in
     * [pickAdapter] above so they don't reach this code path. First-frame
     * magic-byte disambiguation for blank-named Begode / Veteran lives in the
     * per-adapter `onRawNotification` and can be added without changing here.
     */
    override fun pickAdapterByDiscoveredServices(
        discoveredServiceUuids: Set<String>,
        deviceName: String?
    ): Boolean {
        // V1 exposes BOTH the bare HM-10 0xFFE0 service AND a second 0xFFE5
        // service for writes; presence of FFE5 is the unambiguous tell.
        val v1WriteServiceUuid = "0000ffe5-0000-1000-8000-00805f9b34fb"
        val newActive = when {
            BleProfile.NORDIC_UART.serviceUuid in discoveredServiceUuids -> inmotion
            v1WriteServiceUuid in discoveredServiceUuids -> inmotionV1
            BleProfile.HM10.serviceUuid in discoveredServiceUuids -> kingsong
            else -> return false
        }
        if (newActive !== active) {
            active = newActive
            // Give the newly-active adapter a chance to pre-select a sub-model
            // from the BLE name, the same hook the cold-connect path uses.
            active.notifyConnectingTo(deviceName)
        }
        return true
    }

    override fun initSequence(): List<ByteArray> = active.initSequence()
    override fun pollRealtime(): ByteArray = active.pollRealtime()
    override fun pollSettings(): ByteArray = active.pollSettings()

    override fun horn(): ByteArray? = active.horn()
    override fun hornFollowup(): ByteArray? = active.hornFollowup()
    override fun setLight(on: Boolean): ByteArray? = active.setLight(on)
    override fun setLightFollowup(on: Boolean): ByteArray? = active.setLightFollowup(on)
    override fun setMaxSpeed(tiltbackKmh: Float, alarmKmh: Float): ByteArray? =
        active.setMaxSpeed(tiltbackKmh, alarmKmh)
    override fun setMaxSpeedCommit(tiltbackKmh: Float): ByteArray? =
        active.setMaxSpeedCommit(tiltbackKmh)
    override fun setAlarmSpeedCommit(alarmKmh: Float): ByteArray? =
        active.setAlarmSpeedCommit(alarmKmh)

    override fun setVolume(percent: Int): ByteArray? = active.setVolume(percent)
    override fun setDRL(on: Boolean): ByteArray? = active.setDRL(on)
    override fun setLock(locked: Boolean): ByteArray? = active.setLock(locked)
    override fun resetTripMeter(): ByteArray? = active.resetTripMeter()

    override fun requestAuthKey(): ByteArray? = active.requestAuthKey()
    override fun verifyAuth(encryptedKey: ByteArray): ByteArray? = active.verifyAuth(encryptedKey)

    override fun onRawNotification(rawBytes: ByteArray): List<DecodeResult> {
        // Post-connect family rescue. Veteran wheels (Sherman / Patton /
        // Lynx / Lynx S / Abrams / Oryx) share the HM-10 BLE profile with
        // KingSong and Begode, so when a Veteran wheel advertises a name
        // we don't recognise as Veteran (Lynx S in the wild has shipped
        // with names that don't contain "lynx"), the name-based router in
        // [pickAdapter] sticks us on the wrong adapter and the realtime
        // stream parses to zeros. The Veteran magic `DC 5A 5C` is unique
        // enough across the three HM-10 families to swap on first sight:
        // KingSong frames start with `AA 55`, Begode frames start with
        // `55 AA`. We only swap *away from* non-Veteran adapters so we
        // never bounce back and forth on stray bytes that happen to look
        // like the magic.
        if (active !is VeteranAdapter && containsVeteranMagic(rawBytes)) {
            active = veteran
            active.notifyConnectingTo(null)
        }
        return active.onRawNotification(rawBytes)
    }

    private fun containsVeteranMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        var i = 0
        val end = bytes.size - 2
        while (i < end) {
            if (bytes[i] == 0xDC.toByte() &&
                bytes[i + 1] == 0x5A.toByte() &&
                bytes[i + 2] == 0x5C.toByte()
            ) return true
            i++
        }
        return false
    }

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

/**
 * BLE-name allowlist for the default ("known wheels only") scan mode — a faithful
 * port of Android `BleScanner.isLikelyWheel`. The iOS scan otherwise lists every
 * BLE peripheral; this filters it to recognised wheel names (InMotion V1/V2,
 * KingSong, Begode/Gotway, Veteran, Ninebot). The Scan screen offers a "show all"
 * toggle for wheels with an unusual name, matching Android.
 */
fun isLikelyWheel(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    // InMotion V2 family
    if (name.startsWith("Adventure-")) return true
    if (name.startsWith("P6-")) return true
    if (name.startsWith("InMotion")) return true
    // V8-…, V10-…, V11Y-…, V12HS-…: leading V, a digit, then at least one more char.
    if (name.length >= 3 && name[0] == 'V' && name[1].isDigit()) {
        var i = 2
        while (i < name.length && name[i].isDigit()) i++
        if (i < name.length) return true
    }
    // InMotion V1 legacy: "IM<digits>", "L6-", "Lively", "Glide" / "Solowheel".
    if (name.length >= 3 && (name[0] == 'I' || name[0] == 'i') &&
        (name[1] == 'M' || name[1] == 'm') && name[2].isDigit()) return true
    if (name.startsWith("L6-", ignoreCase = true)) return true
    if (name.startsWith("Lively", ignoreCase = true)) return true
    if (name.startsWith("Glide", ignoreCase = true) ||
        name.startsWith("Solowheel", ignoreCase = true)) return true
    // KingSong
    if (name.startsWith("KS-") || name.startsWith("KS ") ||
        name.startsWith("KingSong", ignoreCase = true)) return true
    if (Regex("^S(?:1[6-9]|2[02])(?:\\b|[-_ ])").containsMatchIn(name)) return true
    if (name.startsWith("F18P", ignoreCase = true) ||
        name.startsWith("F22P", ignoreCase = true)) return true
    // Begode/Gotway
    if (name.startsWith("Gotway", ignoreCase = true) ||
        name.startsWith("Begode", ignoreCase = true) ||
        name.startsWith("Master_", ignoreCase = true) ||
        name.startsWith("RS_", ignoreCase = true) || name.startsWith("RS-", ignoreCase = true) ||
        name.startsWith("EX_", ignoreCase = true) || name.startsWith("EX.", ignoreCase = true) ||
        name.startsWith("EX2", ignoreCase = true) ||
        name.startsWith("MSP", ignoreCase = true) || name.startsWith("MSX", ignoreCase = true) ||
        name.startsWith("Mten", ignoreCase = true) || name.startsWith("MCM5", ignoreCase = true) ||
        name.startsWith("Hero", ignoreCase = true) ||
        name.startsWith("T3", ignoreCase = true) || name.startsWith("T4", ignoreCase = true)) return true
    // Veteran
    val nl = name.lowercase()
    if ("sherman" in nl || "patton" in nl || "abrams" in nl ||
        Regex("\\blynx\\b").containsMatchIn(nl)) return true
    // Ninebot / Segway-Ninebot
    if (name.startsWith("Ninebot", ignoreCase = true) ||
        name.startsWith("Segway", ignoreCase = true)) return true
    if (Regex("^ZN\\d", RegexOption.IGNORE_CASE).containsMatchIn(name)) return true
    if (name.startsWith("MiniPLUS", ignoreCase = true) ||
        name.startsWith("Mini Plus", ignoreCase = true)) return true
    return false
}
