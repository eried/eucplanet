package com.eried.eucplanet.ble

/**
 * Models in the Veteran BLE protocol family. Veteran's `DC 5A 5C 20`-prefixed
 * frame carries a model byte at offset 6 (versionLow) and a hardware-revision
 * byte at offset 7 (versionHigh) per WheelLog's research; we don't try to map
 * every (lo, hi) pair here; instead we derive the enum from the BLE-advertised
 * device name when possible, and fall back to a generic Veteran profile.
 *
 * Spec: docs/protocols/veteran.md. Protocol research credit goes to WheelLog
 * community; the implementation here is original.
 */
enum class VeteranModel(
    val displayName: String,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int
) {
    // mVer 0/1: Sherman 100 V curve
    SHERMAN(       "Veteran Sherman",      100,  65),
    // mVer 2: Abrams shares the same 100 V battery curve as Sherman per
    // WheelLog. We previously mis-tagged this at 168 V which routed it
    // through the wrong scaler and caused riders to see a flat 100 % battery.
    ABRAMS(        "Veteran Abrams",       100, 120),
    // mVer 3: Sherman S (still in the <4 / 100 V battery family per WL)
    SHERMAN_S(     "Veteran Sherman S",    134, 100),
    // Retained for backward compatibility with existing settings; no
    // WheelLog equivalent. Treated as Sherman at runtime.
    SHERMAN_MAX(   "Veteran Sherman Max",  134, 100),
    // mVer 4 / 7 / 43: Patton family (134 V curve)
    PATTON(        "Veteran Patton",       134, 110),
    PATTON_S(      "Veteran Patton S",     134, 110),
    NOSFET_AERO(   "Veteran Nosfet Aero",  134, 110),
    // mVer 5 / 6 / 9 / 42 / 44: Lynx / Sherman L / Nosfet (151 V curve)
    LYNX(          "Veteran Lynx",         151, 120),
    LYNX_S(        "Veteran Lynx S",       151, 120),
    SHERMAN_L(     "Veteran Sherman L",    151, 120),
    NOSFET_APEX(   "Veteran Nosfet Apex",  151, 120),
    NOSFET_AEON(   "Veteran Nosfet Aeon",  151, 120),
    // mVer 8: Oryx, dual-pack 218 V class
    ORYX(          "Veteran Oryx",         218, 130);

    companion object {
        fun fromReportedName(name: String): VeteranModel? {
            val n = name.lowercase()
            return when {
                "nosfet apex" in n || "nosfetapex" in n -> NOSFET_APEX
                "nosfet aero" in n || "nosfetaero" in n -> NOSFET_AERO
                "nosfet aeon" in n || "nosfetaeon" in n -> NOSFET_AEON
                "oryx"   in n -> ORYX
                "lynx s" in n || "lynxs" in n -> LYNX_S
                "lynx"   in n -> LYNX
                "patton s" in n || "pattons" in n -> PATTON_S
                "patton" in n -> PATTON
                "abrams" in n -> ABRAMS
                "sherman max" in n || "shermanmax" in n -> SHERMAN_MAX
                "sherman l" in n || "shermanl" in n -> SHERMAN_L
                "sherman s" in n || "shermans" in n -> SHERMAN_S
                "sherman" in n -> SHERMAN
                else -> null
            }
        }

        /**
         * Map the `mVer` value the wheel reports at frame offset 28
         * (u16 BE / 1000 per WheelLog's convention) to a concrete model.
         * This is authoritative when the BLE name is generic; many wheels
         * advertise as just "Veteran-xxxx" with no model token.
         */
        fun fromMVer(mVer: Int): VeteranModel? = when (mVer) {
            0, 1 -> SHERMAN
            2 -> ABRAMS
            3 -> SHERMAN_S
            4 -> PATTON
            5 -> LYNX
            6 -> SHERMAN_L
            7 -> PATTON_S
            8 -> ORYX
            9 -> LYNX_S
            42 -> NOSFET_APEX
            43 -> NOSFET_AERO
            44 -> NOSFET_AEON
            else -> null
        }
    }
}
