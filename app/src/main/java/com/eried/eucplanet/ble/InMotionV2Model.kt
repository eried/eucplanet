package com.eried.eucplanet.ble

/**
 * Models in the InMotion V2 protocol family. The ID is encoded as `series*10+type`
 * in the `MainInfo` response (cmd 0x02, sub 0x01) — exactly what WheelLog calls
 * `Model.findById(modelId)`.
 *
 * Each model has flags that drive per-model command and parser dispatch in
 * [InMotionV2Adapter]. Source of truth for these mappings is WheelLog's
 * `InmotionAdapterV2.java` (lines 118–272 in the master branch as of this writing).
 *
 * Phase 2 only declares the registry; per-model command branching lands in
 * Phase 3 when V12/V11/V13 owners can validate against real hardware.
 */
enum class InMotionV2Model(
    val modelId: Int,
    val displayName: String,
    /**
     * V14-style command (sub 0x21) that packs both tiltback and beep alarm
     * thresholds into one packet. False means the older V11/V12HS/HT/Pro form
     * which only sets tiltback.
     */
    val maxSpeedHasAlarms: Boolean,
    /**
     * Horn opcode varies: V13/V14/V11Y use `playBeep(0x02)`, the older models
     * use `playSound(0x18)`. Stored as the sub-cmd byte.
     */
    val hornOpcode: Byte,
    /**
     * Upper bound for the user-configurable tiltback slider in km/h. Numbers
     * mirror WheelLog's `InmotionAdapterV2.getMaxSpeed()` table; P6 isn't in
     * WheelLog so it gets 130 km/h to match community-reported top speeds.
     * This is *not* the firmware-enforced cap — V14 firmware steps
     * 70 → 80 → 90 km/h depending on revision and break-in mileage. The slider
     * just lets the user request up to this value; the wheel clamps further
     * if needed and the repository's reconcile logic now keeps the user's
     * stored value rather than overwriting it with the clamp.
     */
    val maxSpeedKmh: Int
) {
    /**
     * The P6 actually uses an extended-routing-only variant of the V2 protocol
     * (every query goes `02 21 [sub]`, every response `21 02 [sub|0x80]`) per
     * BLE captures. We don't have a P6 parser yet — this entry exists so the
     * developer P6 simulator can identify itself, and so future Phase 4 work
     * has the model already wired in. The flags here are placeholders that
     * keep the simulator on the V14 command path.
     */
    P6(     21, "InMotion P6",         maxSpeedHasAlarms = true,  hornOpcode = 0x18, maxSpeedKmh = 130),
    V11(    61, "InMotion V11",        maxSpeedHasAlarms = false, hornOpcode = 0x18, maxSpeedKmh = 60),
    V11Y(   62, "InMotion V11y",       maxSpeedHasAlarms = true,  hornOpcode = 0x02, maxSpeedKmh = 120),
    V12HS(  71, "InMotion V12 HS",     maxSpeedHasAlarms = false, hornOpcode = 0x18, maxSpeedKmh = 70),
    V12HT(  72, "InMotion V12 HT",     maxSpeedHasAlarms = false, hornOpcode = 0x18, maxSpeedKmh = 70),
    V12PRO( 73, "InMotion V12 Pro",    maxSpeedHasAlarms = false, hornOpcode = 0x18, maxSpeedKmh = 70),
    V13(    81, "InMotion V13",        maxSpeedHasAlarms = true,  hornOpcode = 0x02, maxSpeedKmh = 120),
    V13PRO( 82, "InMotion V13 Pro",    maxSpeedHasAlarms = true,  hornOpcode = 0x02, maxSpeedKmh = 120),
    V14_50GB(91, "InMotion V14 50GB",  maxSpeedHasAlarms = true,  hornOpcode = 0x02, maxSpeedKmh = 120),
    V14_50S( 92, "InMotion V14 50S",   maxSpeedHasAlarms = true,  hornOpcode = 0x02, maxSpeedKmh = 120),
    V12S(  111, "InMotion V12S",       maxSpeedHasAlarms = true,  hornOpcode = 0x18, maxSpeedKmh = 120),
    V9(    121, "InMotion V9",         maxSpeedHasAlarms = true,  hornOpcode = 0x18, maxSpeedKmh = 120);

    companion object {
        /** Horn sub-cmd byte for `playBeep` (V13/V14/V11Y per WheelLog). */
        const val HORN_PLAY_BEEP: Byte = 0x02
        /** Horn sub-cmd byte for `playSound` (V11/V12HS/HT/PRO/V12S/V9 per WheelLog). */
        const val HORN_PLAY_SOUND: Byte = 0x18

        fun fromId(modelId: Int): InMotionV2Model? =
            values().firstOrNull { it.modelId == modelId }
    }
}
