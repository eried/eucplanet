package com.eried.eucplanet.ble

/**
 * Models in the KingSong BLE protocol family. KingSong does NOT report a
 * numeric model ID over BLE; the model is the ASCII string carried in the
 * `0xBB` (name) frame. We keep an enum for capability gating, voltage-class
 * coercion, and display name normalization.
 *
 * Per-model voltage class drives the battery-percent curve (Li-ion EUC packs
 * have well-defined linear regions between full-charge and empty).
 *
 * Spec: docs/protocols/kingsong.md. Protocol research credit goes to WheelLog
 * (Ilya Shkolnik and contributors); the implementation here is original.
 */
enum class KingsongModel(
    val displayName: String,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int
) {
    KS14(   "KingSong KS-14",   67,  35),
    KS16(   "KingSong KS-16",   67,  40),
    KS_16X( "KingSong KS-16X",  84,  50),
    KS_16S( "KingSong KS-16S",  84,  50),
    KS18(   "KingSong KS-18",   84,  50),
    KS_S16( "KingSong KS-S16",  84,  55),
    KS_S18( "KingSong KS-S18",  84,  60),
    KS_S19( "KingSong KS-S19", 100,  65),
    KS_S20( "KingSong KS-S20", 126,  85),
    KS_S22( "KingSong KS-S22", 126, 100),
    KS_F18P("KingSong F18P",   151, 110),
    KS_F22P("KingSong F22P",   176, 120);

    companion object {
        /**
         * Best-effort match of the wheel's reported name string to an enum
         * value. KingSong firmware reports names like "KS-S22-1234" or
         * "S22 100km/h"; we normalize to lowercase and look for the model
         * token. Returns null when no obvious match; the adapter will
         * still operate, it just won't apply per-model capability nudges.
         */
        fun fromReportedName(name: String): KingsongModel? {
            val n = name.lowercase()
            return when {
                "s22" in n  -> KS_S22
                "s20" in n  -> KS_S20
                "s19" in n  -> KS_S19
                "s18" in n  -> KS_S18
                "s16" in n  -> KS_S16
                "f22" in n  -> KS_F22P
                "f18" in n  -> KS_F18P
                "ks-18" in n || "ks18" in n -> KS18
                // Order matters: match the longer "X" / "S" suffixes before the
                // plain "ks-16" so KS-16X (84 V) isn't mis-classified as the
                // legacy KS-16 (67 V) which would skew the battery curve.
                "ks-16x" in n || "ks16x" in n -> KS_16X
                "ks-16s" in n || "ks16s" in n -> KS_16S
                "ks-16" in n || "ks16" in n -> KS16
                "ks-14" in n || "ks14" in n -> KS14
                else -> null
            }
        }
    }
}
