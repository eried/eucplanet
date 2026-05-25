package com.eried.eucplanet.ble

/**
 * Models in the Ninebot / Segway-Ninebot BLE protocol families. Two distinct
 * wire formats live under the same brand, so the model registry doubles as the
 * protocol selector. The adapter branches on [protocol] before reading any
 * other per-model field.
 *
 * Z protocol (Z6 / Z10 / new-stack E+ / Mini Plus): Nordic-UART GATT, encrypted
 * `5A A5` framing with a 16-byte XOR keystream. Full settings surface (lock,
 * speed limit, alarms, LED, volume, drive flags).
 *
 * Legacy protocol (One E / E+ / S2 / Mini / Mini Pro): HM-10 GATT, plaintext
 * `55 AA` framing. Read-only telemetry; no documented settings writes.
 *
 * Spec: docs/protocols/ninebot.md. Protocol research credit goes to WheelLog
 * (Ilya Shkolnik / Palachzzz and contributors); the implementation here is
 * original.
 */
enum class NinebotProtocol {
    /** Modern Z stack: Nordic-UART, XOR-encrypted, distinct write+notify chars. */
    Z,

    /** Legacy One/S2/Mini stack: HM-10, plaintext, single ffe1 char. */
    LEGACY
}

/**
 * Per-legacy-variant address subset. The phone's source address differs
 * between One/S2/Mini families, and the live-data speed offset moves between
 * variants too. Picked from the BleVersion reply ASCII tag (section 12 of
 * the spec); when the tag isn't available we fall back to DEFAULT.
 */
enum class NinebotLegacyVariant(val appAddress: Int) {
    DEFAULT(0x09),
    S2(0x11),
    MINI(0x0A);
}

enum class NinebotModel(
    val displayName: String,
    val protocol: NinebotProtocol,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int,
    val legacyVariant: NinebotLegacyVariant? = null
) {
    // Z protocol (encrypted Nordic-UART stack)
    Z6(   "Ninebot Z6",  NinebotProtocol.Z,      63, 30),
    Z10(  "Ninebot Z10", NinebotProtocol.Z,      84, 45),

    // Legacy protocol (plaintext HM-10 stack). Per-variant App address comes
    // from the BleVersion ASCII tag; encode it on the model so the parser can
    // derive a sensible default before the tag arrives.
    ONE_E(   "Ninebot One E",     NinebotProtocol.LEGACY, 63, 25, NinebotLegacyVariant.DEFAULT),
    ONE_E_PLUS("Ninebot One E+",  NinebotProtocol.LEGACY, 63, 25, NinebotLegacyVariant.DEFAULT),
    ONE_S2(  "Ninebot One S2",    NinebotProtocol.LEGACY, 63, 30, NinebotLegacyVariant.S2),
    MINI(    "Ninebot Mini",      NinebotProtocol.LEGACY, 36, 16, NinebotLegacyVariant.MINI),
    MINI_PRO("Ninebot Mini Pro",  NinebotProtocol.LEGACY, 36, 18, NinebotLegacyVariant.MINI);

    companion object {
        /**
         * Best-effort match of the BLE-advertised name. `Ninebot Z*`,
         * `Segway Z*`, and the bare `ZN<serial>` form all route to the Z
         * protocol; `Ninebot One*`, `Ninebot S2*`, `Ninebot Mini*` route to
         * legacy. Returns null if no obvious match; the adapter then
         * defaults to the legacy protocol (cheaper to be wrong toward
         * read-only than to send encrypted writes to a plaintext wheel).
         *
         * Note: the spec calls out that Mini Plus often advertises a
         * legacy-style name yet speaks Z. Post-connect detection (probing
         * the Nordic-UART service UUID) is what actually decides which
         * profile to bind to; this is a pre-connect hint only.
         */
        fun fromReportedName(name: String): NinebotModel? {
            val n = name.lowercase()
            return when {
                "z10" in n -> Z10
                "z6" in n  -> Z6
                // Bare "ZN<serial>" form used by some Z6 firmwares.
                Regex("^zn\\d").containsMatchIn(n) -> Z6
                "miniplus" in n || "mini plus" in n -> Z6 // Mini Plus speaks Z; treat as Z generically
                "mini pro" in n -> MINI_PRO
                "mini" in n -> MINI
                "s2" in n -> ONE_S2
                "one e+" in n || "one e plus" in n -> ONE_E_PLUS
                "one e" in n -> ONE_E
                else -> null
            }
        }
    }
}
