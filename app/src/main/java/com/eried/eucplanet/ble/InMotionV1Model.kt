package com.eried.eucplanet.ble

/**
 * Models in the InMotion V1 protocol family — the older V8 / V10 / V5 / L6
 * generation that pre-dates the V14-era V2 wire format. The wheel reports its
 * model code as two ASCII digits at offsets 104 and 107 of the slow-info reply
 * (see docs/protocols/inmotion_v1.md section 5.1); the two bytes are
 * concatenated as `[byte107 if > 0][byte104]` and looked up here.
 *
 * Per-model fields drive parser dispatch (speed factor, mileage encoding,
 * battery curve) and capability gating. Defaults match the spec table in
 * section 4.1 — `speedFactor` is 3812 for the V8 / V10 generation and 1000
 * only for the legacy R-series (R1S / R0).
 *
 * Spec: docs/protocols/inmotion_v1.md. Protocol research credit goes to
 * WheelLog (Ilya Shkolnik and contributors); the implementation here is original.
 */
enum class InMotionV1Model(
    val modelId: Int,
    val displayName: String,
    val nominalVoltage: Int,
    val maxSpeedKmh: Int,
    val speedFactor: Float
) {
    R0(   30, "InMotion R0",        67, 35, 1000f),
    V5(   50, "InMotion V5",        67, 25, 3812f),
    V5PLUS(51, "InMotion V5+",      67, 25, 3812f),
    V5F(  52, "InMotion V5F",       67, 30, 3812f),
    V5D(  53, "InMotion V5D",       67, 25, 3812f),
    L6(   60, "InMotion L6",        67, 25, 3812f),
    LIVELY(61, "InMotion Lively",   67, 25, 3812f),
    V8(   80, "InMotion V8",        84, 35, 3812f),
    GLIDE3(85, "Solowheel Glide 3", 84, 35, 3812f),
    V8F(  86, "InMotion V8F",       84, 45, 3812f),
    V8S(  87, "InMotion V8S",       84, 45, 3812f),
    V10S( 100,"InMotion V10S",      84, 40, 3812f),
    V10SF(101,"InMotion V10SF",     84, 40, 3812f),
    V10(  140,"InMotion V10",       84, 40, 3812f),
    V10F( 141,"InMotion V10F",      84, 45, 3812f),
    V10T( 142,"InMotion V10T",      84, 40, 3812f),
    V10FT(143,"InMotion V10FT",     84, 45, 3812f);

    /** V8 / V10 line uses the modern work-mode encoding (high nibble of low byte). */
    val isModern: Boolean get() = modelId in setOf(86, 87, 85, 100, 101, 140, 141, 142, 143)

    /** V10 family uses the wider "better" battery curve; V8 family uses the V8 curve. */
    val isV10Family: Boolean get() = modelId in setOf(100, 101, 140, 141, 142, 143)

    /** V8 / V8F / V8S / Glide 3 share the V8-class (84 V) battery curve. */
    val isV8Family: Boolean get() = modelId in setOf(80, 85, 86, 87)

    /** L6 mileage at offset 44 is u64 in centimetres; everything else is u32 in metres. */
    val isL6: Boolean get() = modelId == 60

    /** R0 mileage at offset 44 is u64 in metres, per WheelLog `WL:1128`. */
    val isR0: Boolean get() = modelId == 30

    /** V8F / V8S / V10 family / Glide 3 expose the dedicated horn opcode `0xB2 ... 0x11`. */
    val hasDedicatedHorn: Boolean get() = isV10Family || modelId in setOf(85, 86, 87)

    /** V8F / V8S / V10 family / Glide 3 ship a working speaker volume slider. */
    val hasVolume: Boolean get() = isV10Family || modelId in setOf(85, 86, 87)

    /** V8 / V8F / V8S / Glide 3 / V10 family expose the under-glow LED toggle. */
    val hasDRL: Boolean get() = isV10Family || isV8Family

    companion object {
        /**
         * Decode the wheel's model code from the two ASCII bytes returned at
         * offsets 104 and 107 of the slow-info reply. Per spec 5.1 the value
         * is `[byte107 if > 0][byte104]` parsed as a decimal integer; bytes
         * are ASCII digits, so we mask to 0..9 and pack high*10 + low.
         *
         * NOTE: with two ASCII digits we can only express ids 0..99. The V10
         * family (ids 100, 101, 140-143) is documented in spec 5.1 but
         * unreachable here because the spec offsets only specify two bytes.
         * V10/V10F/V10S/V10T/V10FT wheels are still identified at connect
         * time via [fromReportedName] from the BLE-advertised name, so the
         * dashboard does the right thing in practice. Lift this if a labelled
         * capture clarifies whether a third digit lives at another offset.
         */
        fun fromCarType(low: Int, high: Int): InMotionV1Model? {
            // WheelLog: the bytes are raw decimal numbers, not packed BCD.
            // For id 143 (e.g. V10F), byte[107]=1, byte[104]=43, concatenated
            // as the string "143". For 2-digit ids, high is zero and only
            // low is meaningful. We were ANDing each byte with 0x0F which
            // destroyed any id >= 100 (low byte 43 -> 3, id became 13).
            val id = if (high > 0) high * 100 + low else low
            return values().firstOrNull { it.modelId == id }
        }

        /**
         * Best-effort match from the BLE advertised name. V1 firmwares publish
         * names like `V8F-AB12`, `Inmotion-V10F`, `IM01234`, `L6-XXXX` or
         * `Glide 3`. Returns null when no obvious match — the adapter still
         * connects and resolves the model from the slow-info reply.
         */
        fun fromReportedName(name: String): InMotionV1Model? {
            val n = name.lowercase()
            return when {
                "v10ft" in n -> V10FT
                "v10t" in n  -> V10T
                "v10sf" in n -> V10SF
                "v10s" in n  -> V10S
                "v10f" in n  -> V10F
                "v10" in n   -> V10
                "v8s" in n   -> V8S
                "v8f" in n   -> V8F
                "v8" in n    -> V8
                "glide" in n || "solowheel" in n -> GLIDE3
                "v5plus" in n || "v5+" in n -> V5PLUS
                "v5f" in n   -> V5F
                "v5d" in n   -> V5D
                "v5" in n    -> V5
                "lively" in n -> LIVELY
                "l6" in n    -> L6
                else -> null
            }
        }
    }
}
