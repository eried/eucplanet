package com.eried.eucplanet.ble

/**
 * Models in the Veteran BLE protocol family. Veteran's `DC 5A 5C 20`-prefixed
 * frame carries a model byte at offset 6 (versionLow) and a hardware-revision
 * byte at offset 7 (versionHigh) per WheelLog's research; we don't try to map
 * every (lo, hi) pair here — instead we derive the enum from the BLE-advertised
 * device name when possible, and fall back to a generic Veteran profile.
 *
 * Spec: docs/protocols/veteran.md. Protocol research credit goes to WheelLog
 * (Ilya Shkolnik and contributors); the implementation here is original.
 */
enum class VeteranModel(
    val displayName: String,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int
) {
    SHERMAN(       "Veteran Sherman",      100,  65),
    SHERMAN_S(     "Veteran Sherman S",    134, 100),
    SHERMAN_MAX(   "Veteran Sherman Max",  134, 100),
    PATTON(        "Veteran Patton",       151, 110),
    LYNX(          "Veteran Lynx",         151, 120),
    ABRAMS(        "Veteran Abrams",       168, 120);

    companion object {
        fun fromReportedName(name: String): VeteranModel? {
            val n = name.lowercase()
            return when {
                "abrams" in n -> ABRAMS
                "lynx"   in n -> LYNX
                "patton" in n -> PATTON
                "sherman max" in n || "shermanmax" in n -> SHERMAN_MAX
                "sherman s" in n || "shermans" in n -> SHERMAN_S
                "sherman" in n -> SHERMAN
                else -> null
            }
        }
    }
}
