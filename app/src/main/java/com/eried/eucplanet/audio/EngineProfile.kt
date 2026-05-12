package com.eried.eucplanet.audio

/**
 * A virtual engine personality. Drives [EngineSynth].
 *
 * Two flavors:
 *  - [Kind.ICE]   — internal combustion model. Per-cylinder firing pulses at
 *    [firingAngles] in the crank cycle, harmonics layered on top, exhaust grit
 *    noise modulated by load.
 *  - [Kind.SYNTH] — pure oscillator/FM. Frequency rides on virtual RPM with
 *    [baseHz]..[maxHz]; no cylinder model, no gearbox.
 *
 * Profiles are stateless — runtime state lives in [EngineParams].
 */
data class EngineProfile(
    /** Stable ID used in settings storage. */
    val key: String,
    /** Display label for UI (English; localized via strings.xml resolveDisplayName). */
    val displayName: String,
    val kind: Kind,
    /** Lowest "running" RPM. Idle revs around here. */
    val idleRpm: Int,
    /** Redline RPM at full throttle + top speed. */
    val maxRpm: Int,
    /** True for engines that shouldn't pretend to have gears (synth, very small singles). */
    val gearless: Boolean = false,
    /** Cylinder count (ICE only). */
    val cylinderCount: Int = 1,
    /**
     * Firing angles within a single engine cycle (in degrees).
     * 4-stroke = 720° cycle. 2-stroke = 360° cycle. SYNTH = ignored.
     */
    val firingAngles: FloatArray = floatArrayOf(0f),
    /** True for 2-stroke (cycle = 360°), false for 4-stroke (cycle = 720°). */
    val twoStroke: Boolean = false,
    /**
     * Harmonic series amplitudes for the fundamental + overtones (h1..h6).
     * h1 is the engine firing pulse fundamental; higher harmonics add brightness.
     */
    val harmonics: FloatArray = floatArrayOf(1f, 0.6f, 0.35f, 0.2f, 0.12f, 0.07f),
    /** Mix of exhaust hiss/noise; richer at high load. */
    val exhaustGrit: Float = 0.15f,
    /** Pop probability per decel event (0..1) — multiplied by the user's decel character. */
    val decelPopProbability: Float = 0.4f,
    /** Brightness of the firing pulse — 1 = punchy click, 0 = soft thump. */
    val compressionTone: Float = 0.5f,
    // --- SYNTH-only params ---
    /** Synth base frequency at idle (Hz). */
    val synthBaseHz: Float = 80f,
    /** Synth peak frequency at max RPM (Hz). */
    val synthMaxHz: Float = 1200f,
    /** Synth waveform shape — 0 = sine, 1 = saw, 2 = square. */
    val synthShape: Int = 1,
    /** FM modulation depth for synth (0..1). */
    val synthFmDepth: Float = 0.3f,
    /**
     * Resource basename for sample-based rendering, looked up under `res/raw/`.
     *
     * - `${sampleAssetBase}_idle` is the looped idle sample (required)
     * - `${sampleAssetBase}_rev`  is the optional mid/high-RPM loop (crossfaded by load)
     *
     * When set, the engine is rendered by playing back and pitch-shifting these
     * samples instead of the procedural [EngineSynth]. Null = procedural.
     */
    val sampleAssetBase: String? = null
) {
    enum class Kind { ICE, SYNTH }

    /** Engine cycle in degrees (720 for 4-stroke, 360 for 2-stroke). */
    val cycleDegrees: Float get() = if (twoStroke) 360f else 720f

    companion object {
        /**
         * Catalog of v1 presets. Order is also the picker order in the UI.
         * Strings.xml lookup happens at render time via [displayNameResId].
         */
        val PROFILES: List<EngineProfile> = listOf(
            // --- Singles & small thumpers (gearless because EUCs don't shift like these) ---
            EngineProfile(
                key = "TWO_STROKE",
                displayName = "2-stroke",
                kind = Kind.ICE,
                idleRpm = 1400, maxRpm = 11000,
                gearless = true,                       // 2-stroke wheels usually don't shift on EUC sim
                cylinderCount = 1, twoStroke = true,
                firingAngles = floatArrayOf(0f),
                harmonics = floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.4f, 0.25f),
                exhaustGrit = 0.4f,
                decelPopProbability = 0.25f,
                compressionTone = 0.85f
            ),
            EngineProfile(
                key = "FOUR_STROKE_SINGLE",
                displayName = "4-stroke single",
                kind = Kind.ICE,
                idleRpm = 900, maxRpm = 7500,
                cylinderCount = 1,
                firingAngles = floatArrayOf(0f),
                harmonics = floatArrayOf(1f, 0.55f, 0.3f, 0.18f, 0.1f, 0.05f),
                exhaustGrit = 0.18f,
                decelPopProbability = 0.45f,
                compressionTone = 0.6f
            ),
            EngineProfile(
                key = "QUAD_BIKE",
                displayName = "Quad bike",
                kind = Kind.ICE,
                idleRpm = 850, maxRpm = 6200,
                gearless = true,                        // most ATVs are CVT to the rider's ear
                cylinderCount = 1,
                firingAngles = floatArrayOf(0f),
                harmonics = floatArrayOf(1f, 0.7f, 0.5f, 0.3f, 0.18f, 0.1f),
                exhaustGrit = 0.3f,
                decelPopProbability = 0.3f,
                compressionTone = 0.7f
            ),
            // --- Multi-cylinder ICE ---
            EngineProfile(
                key = "V_TWIN",
                displayName = "V-twin",
                kind = Kind.ICE,
                idleRpm = 950, maxRpm = 6500,
                cylinderCount = 2,
                // 45° V-twin: classic uneven "potato-potato" — second cylinder fires 315° after the first
                firingAngles = floatArrayOf(0f, 315f),
                harmonics = floatArrayOf(1f, 0.65f, 0.4f, 0.22f, 0.12f, 0.06f),
                exhaustGrit = 0.22f,
                decelPopProbability = 0.5f,
                compressionTone = 0.65f
            ),
            EngineProfile(
                key = "INLINE_4",
                displayName = "Inline-4",
                kind = Kind.ICE,
                idleRpm = 1100, maxRpm = 13500,
                cylinderCount = 4,
                firingAngles = floatArrayOf(0f, 180f, 360f, 540f),
                harmonics = floatArrayOf(1f, 0.5f, 0.3f, 0.2f, 0.12f, 0.07f),
                exhaustGrit = 0.16f,
                decelPopProbability = 0.5f,
                compressionTone = 0.55f
            ),
            EngineProfile(
                key = "V8_MUSCLE",
                displayName = "V8 muscle",
                kind = Kind.ICE,
                idleRpm = 700, maxRpm = 7000,
                cylinderCount = 8,
                firingAngles = floatArrayOf(0f, 90f, 180f, 270f, 360f, 450f, 540f, 630f),
                harmonics = floatArrayOf(1f, 0.7f, 0.5f, 0.35f, 0.22f, 0.13f),
                exhaustGrit = 0.28f,
                decelPopProbability = 0.55f,
                compressionTone = 0.7f
            ),
            EngineProfile(
                key = "V12_ITALIAN",
                displayName = "V12 (Italian)",
                kind = Kind.ICE,
                idleRpm = 800, maxRpm = 8500,
                cylinderCount = 12,
                firingAngles = FloatArray(12) { i -> i * 60f },
                harmonics = floatArrayOf(1f, 0.75f, 0.55f, 0.4f, 0.27f, 0.16f),
                exhaustGrit = 0.14f,
                decelPopProbability = 0.35f,
                compressionTone = 0.55f
            ),
            EngineProfile(
                key = "V16_LUXURY",
                displayName = "V16 (luxury)",
                kind = Kind.ICE,
                idleRpm = 550, maxRpm = 6500,
                cylinderCount = 16,
                firingAngles = FloatArray(16) { i -> i * 45f },
                harmonics = floatArrayOf(1f, 0.85f, 0.65f, 0.45f, 0.3f, 0.18f),
                exhaustGrit = 0.1f,
                decelPopProbability = 0.2f,
                compressionTone = 0.45f
            ),
            // --- Synth / futuristic (gearless) ---
            EngineProfile(
                key = "TRON_WHINE",
                displayName = "Tron whine",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 600, maxRpm = 14000,
                synthBaseHz = 110f, synthMaxHz = 2400f,
                synthShape = 2,           // square — bright digital edge
                synthFmDepth = 0.15f,
                exhaustGrit = 0.05f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "POD_RACER",
                displayName = "Pod racer",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 500, maxRpm = 10000,
                synthBaseHz = 65f, synthMaxHz = 950f,
                synthShape = 1,           // saw — gritty turbine
                synthFmDepth = 0.6f,
                exhaustGrit = 0.45f,
                decelPopProbability = 0.0f
            ),
            // --- Sampled (real recordings, all CC0 from BigSoundBank — see Credits) ---
            // For sampled engines the procedural [kind] flags below are unused; the SampledEnginePlayer
            // handles render. Idle/max RPM still drive the playback-speed mapping.
            EngineProfile(
                key = "SAMPLED_V8_COBRA",
                displayName = "V8 (Cobra, sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 700, maxRpm = 6500,
                sampleAssetBase = "engine_v8_cobra"
            ),
            EngineProfile(
                key = "SAMPLED_VTWIN_DUCATI",
                displayName = "V-twin (Ducati, sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 950, maxRpm = 9000,
                sampleAssetBase = "engine_vtwin_ducati"
            ),
            EngineProfile(
                key = "SAMPLED_DIESEL_IVECO",
                displayName = "Diesel truck (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 800, maxRpm = 3200,
                sampleAssetBase = "engine_diesel_iveco"
            ),
            EngineProfile(
                key = "SAMPLED_MOTORCYCLE",
                displayName = "Motorcycle (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 1100, maxRpm = 10500,
                sampleAssetBase = "engine_motorcycle"
            ),
            EngineProfile(
                key = "SAMPLED_CITY_CAR",
                displayName = "City car (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 850, maxRpm = 6000,
                sampleAssetBase = "engine_citycar_saxo"
            )
        )

        /** Returns the profile for [key] or the default 4-stroke single if unknown. */
        fun byKey(key: String): EngineProfile =
            PROFILES.firstOrNull { it.key == key } ?: PROFILES.first { it.key == "FOUR_STROKE_SINGLE" }
    }

    // data class equality on FloatArray would compare by reference; we never compare profiles,
    // but keep equals/hashCode aware so unit tests don't bite later.
    override fun equals(other: Any?): Boolean = (other as? EngineProfile)?.key == key
    override fun hashCode(): Int = key.hashCode()
}
