package com.eried.eucplanet.ble

/**
 * Models in the Begode/Gotway BLE protocol family. Begode reports the model as
 * an ASCII firmware-version string (e.g. "GW135.20.16") in the firmware-banner
 * frame; the leading prefix and one model-character byte identify the model.
 *
 * Voltage-class is the most important per-model attribute because Begode's
 * realtime telemetry voltage field is a raw u16 BE that must be scaled by a
 * nominal-voltage-derived ratio (84 V wheels use 0.625, 100 V wheels use ~0.74,
 * 126 V wheels use ~0.94, etc.) to land on real volts.
 *
 * Spec: docs/protocols/begode.md.
 */
enum class BegodeModel(
    val displayName: String,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int
) {
    // Mten3 (Gotway-branded) ships in 67 V (older) and 84 V battery variants.
    // 84 V is by far the more common one in the wild and matches the rated
    // 35 km/h / 23 mph top speed quoted by retailers and the Mten4 spec
    // sheet that shares the drive train (electricunicycles.eu Mten3 vs
    // Mten4 comparison). The 67 V variant tops out closer to 30 km/h but
    // we don't see it often enough to add a separate enum row; 84 V is the
    // safer detection default and the tilt-back UI lets the rider trim
    // further if they have the lower-voltage variant.
    MTEN3(    "Begode Mten3",      84,  35),
    MTEN4(    "Begode Mten4",      84,  35),
    MTEN5(    "Begode Mten5",      84,  35),
    MCM5_V1(  "Begode MCM5 v1",    67,  40),
    MCM5_V2(  "Begode MCM5 v2",    67,  40),
    MSX(      "Begode MSX",       100,  60),
    MSP(      "Begode MSP",       100,  60),
    HERO(     "Begode Hero",      100,  70),
    EX(       "Begode EX",        100,  60),
    EX_N(     "Begode EX.N",      100,  60),
    EX2(      "Begode EX2",       126,  80),
    EX30(     "Begode EX30",      151, 100),
    RS(       "Begode RS",        126,  80),
    RS_HT(    "Begode RS-HT",     134, 100),
    T3(       "Begode T3",         84,  45),
    T4(       "Begode T4",        134, 100),
    MASTER(   "Begode Master",    134, 100),
    MASTER_PRO("Begode Master Pro", 151, 120),
    // Race is a 50S/210V pack: cell-voltage frames show 4.085V x 50 cells
    // = ~204V real, which means the Live-A raw centivolt reading needs
    // the 210V-tier ratio (~3.125), not the 134V one (2.00) used by other
    // Begode flagships. Different protocol class than nominal-voltage
    // peers like the Master.
    RACE(     "Begode Race",      210, 100);

    companion object {
        /**
         * Best-effort match of the wheel's reported name (BLE-advertised) to
         * an enum value. Begode advertises wildly inconsistent names per
         * model and firmware ("RS_5012", "Master_4400", "Gotway_*",
         * "Begode_*"), so this is heuristic. Returns null if no obvious
         * match; the adapter falls back on a generic 84-V profile.
         */
        fun fromReportedName(name: String): BegodeModel? {
            val n = name.lowercase()
            return when {
                "master" in n && "pro" in n -> MASTER_PRO
                "master" in n -> MASTER
                "hero"   in n -> HERO
                "rs-ht"  in n || "rs_ht" in n || "rsht" in n -> RS_HT
                "rs"     in n -> RS
                "ex30"   in n || "ex_30" in n -> EX30
                "ex2"    in n -> EX2
                "ex.n"   in n || "ex_n" in n -> EX_N
                "ex"     in n -> EX
                "msx"    in n -> MSX
                "msp"    in n -> MSP
                "mten5"  in n || "mten 5" in n -> MTEN5
                "mten4"  in n || "mten 4" in n -> MTEN4
                "mten3"  in n || "mten 3" in n -> MTEN3
                "mcm5"   in n -> MCM5_V2
                "t4"     in n -> T4
                "t3"     in n -> T3
                "race"   in n -> RACE
                else -> null
            }
        }
    }
}
