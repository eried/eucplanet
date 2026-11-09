package com.eried.eucplanet.audio

/** How a [EngineProfile.Kind.SYNTH] engine makes its tone. */
enum class SynthVoice {
    /** Sine partials with a slow wobble: hums, saucers, blades. */
    HUM,
    /** Bandlimited saw pair, thick and gritty. */
    SAW,
    /** Bandlimited square with a sub octave: the digital edge. */
    SQUARE,
    /** Shaped noise (rumble and roar) under a spooling shaft whine. */
    TURBINE,
    /** Clean motor whine with its inverter partials and a little wind. */
    ELECTRIC,
}

/**
 * A virtual engine personality. Drives [EngineSynth].
 *
 * Three flavors:
 *  - Recorded: [grainAsset] names a [GrainBank] cut from a real recording of
 *    this engine. The wheel sets how fast the recorded cycles come; the pipes
 *    keep their real pitch. [idleRpm] is the recording's idle and [maxRpm] how
 *    far the wheel may push it.
 *  - [Kind.ICE], internal combustion, synthesised. Each cylinder fires at its
 *    angle of the crank cycle and sends an exhaust pulse into a pipe: a few
 *    resonators tuned by [pipeHz] that give the engine its body, intake and
 *    valve noise rising with load, an optional ring ([ringHz]) for 2-strokes
 *    and screamers. Also what a recorded engine falls back to if its bank is
 *    missing.
 *  - [Kind.SYNTH], a [SynthVoice] whose pitch rides on virtual RPM between
 *    [synthBaseHz] and [synthMaxHz]; no cylinders, no gearbox.
 *
 * Profiles are stateless, runtime state lives in [EngineParams].
 */
data class EngineProfile(
    /** Stable ID used in settings storage. */
    val key: String,
    /** Display label for UI (English; localized via strings.xml engine_preset_<key>). */
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
     * 4-stroke = 720 degree cycle. 2-stroke = 360 degree cycle. SYNTH = ignored.
     */
    val firingAngles: FloatArray = floatArrayOf(0f),
    /** True for 2-stroke (cycle = 360 degrees), false for 4-stroke (cycle = 720 degrees). */
    val twoStroke: Boolean = false,
    /**
     * Which exhaust bank (0 or 1) each firing in [firingAngles] order breathes into.
     * Empty means the firings alternate. A cross-plane V8 does not: each bank gets
     * an uneven run of pulses, which is its burble.
     */
    val banks: IntArray = IntArray(0),
    /** Exhaust pipe body: the lowest resonance in Hz. Big pipes low, small pipes high. */
    val pipeHz: Float = 150f,
    /** How long the pipe rings after each pulse; 1 is a stock muffler, higher is tinnier. */
    val pipeQ: Float = 1f,
    /** A high ring excited by every firing (2-stroke expansion chamber, a V12 scream), 0 for none. */
    val ringHz: Float = 0f,
    val ringAmount: Float = 0f,
    /** Intake roar and valvetrain noise, rises with load. */
    val intakeNoise: Float = 0.3f,
    /** Length of each exhaust pulse in ms. A few ms for a bang, tens of ms for a steam chuff. */
    val pulseMs: Float = 3f,
    /** Mix of exhaust hiss/noise; richer at high load. */
    val exhaustGrit: Float = 0.15f,
    /** Pop probability per decel event (0..1), multiplied by the user's decel character. */
    val decelPopProbability: Float = 0.4f,
    /** Brightness of the firing pulse, 1 = sharp crack, 0 = soft thump. */
    val compressionTone: Float = 0.5f,
    /** Level trim so every engine sits at about the same loudness. */
    val gainTrim: Float = 1f,
    // --- SYNTH-only params ---
    /** Synth base frequency at idle (Hz). */
    val synthBaseHz: Float = 160f,
    /** Synth peak frequency at max RPM (Hz). */
    val synthMaxHz: Float = 1200f,
    val voice: SynthVoice = SynthVoice.SAW,
    /** Wobble depth for synth (0..1): vibrato and tremolo. On a turbine above 0.2 it is a second shaft, detuned, so the two beat. */
    val synthFmDepth: Float = 0.3f,
    /** Turbine voice: how much shaft whine over the air noise. 1 is a jet, less is a ducted fan. */
    val synthWhine: Float = 1f,
    /**
     * `res/raw` name (no extension) of the [GrainBank] a recorded engine plays from,
     * cut by tools/engine-sounds/grains.py from the recordings listed in
     * docs/SFX_SOURCES.md. Null means the engine is synthesised.
     */
    val grainAsset: String? = null,
    // --- Per-engine feature support, drives which rows the Settings panel shows ---
    /** Whether the muffler/exhaust LPF row applies to this engine. */
    val supportsMuffler: Boolean = true,
    /** Whether the decel-pops/backfire row applies to this engine. */
    val supportsPops: Boolean = true,
    /** Whether the engine-brake whine row applies to this engine. */
    val supportsBrakeWhine: Boolean = true,
    /**
     * Preview / hidden engines are excluded from the picker. The full PROFILES list
     * is kept so the runtime can still resolve a stored key (e.g. the user picked
     * "Tron whine" before we marked it preview, opening Settings still shows
     * "Tron whine" as the current value, but the dropdown won't offer it to others).
     */
    val preview: Boolean = false,
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
                gearless = true,
                cylinderCount = 1, twoStroke = true,
                firingAngles = floatArrayOf(0f),
                gainTrim = 1.4f,
                pipeHz = 260f, pipeQ = 1.2f,
                ringHz = 2600f, ringAmount = 0.22f,
                intakeNoise = 0.4f,
                exhaustGrit = 0.4f,
                decelPopProbability = 0.25f,
                compressionTone = 0.8f
            ),
            EngineProfile(
                key = "FOUR_STROKE_SINGLE",
                displayName = "4-stroke single",
                kind = Kind.ICE,
                idleRpm = 900, maxRpm = 7500,
                cylinderCount = 1,
                firingAngles = floatArrayOf(0f),
                pipeHz = 200f, pipeQ = 1f,
                intakeNoise = 0.3f,
                exhaustGrit = 0.18f,
                decelPopProbability = 0.45f,
                compressionTone = 0.6f,
                gainTrim = 2.2f
            ),
            EngineProfile(
                key = "QUAD_BIKE",
                displayName = "Quad bike",
                kind = Kind.ICE,
                // Recorded: a quad started and revved in the yard (BigSoundBank s0712)
                grainAsset = "grain_quad_bike",
                idleRpm = 1000, maxRpm = 3600,
                gearless = true,
                cylinderCount = 1,
                firingAngles = floatArrayOf(0f),
                // A big, lazy single with a fat pipe: deeper and gruffer than the road single
                pipeHz = 170f, pipeQ = 0.8f,
                pulseMs = 4.5f,
                intakeNoise = 0.42f,
                exhaustGrit = 0.35f,
                decelPopProbability = 0.3f,
                compressionTone = 0.6f,
                gainTrim = 2.5f
            ),
            // --- Multi-cylinder ICE ---
            EngineProfile(
                key = "V_TWIN",
                displayName = "V-twin",
                kind = Kind.ICE,
                // Recorded: a Ducati from start to a hard rev (BigSoundBank s0884)
                grainAsset = "grain_v_twin",
                idleRpm = 1000, maxRpm = 6500,
                cylinderCount = 2,
                // 45 degree V-twin: the uneven "potato-potato", second cylinder fires 315 degrees after the first
                firingAngles = floatArrayOf(0f, 315f),
                pipeHz = 210f, pipeQ = 1.1f,
                intakeNoise = 0.5f,
                exhaustGrit = 0.22f,
                decelPopProbability = 0.5f,
                compressionTone = 0.75f,
                gainTrim = 1.7f
            ),
            EngineProfile(
                key = "INLINE_4",
                displayName = "Inline-4",
                kind = Kind.ICE,
                // Recorded: a Citroen Saxo, idle and revs (BigSoundBank s0187)
                grainAsset = "grain_inline_4",
                idleRpm = 900, maxRpm = 4000,
                cylinderCount = 4,
                firingAngles = floatArrayOf(0f, 180f, 360f, 540f),
                gainTrim = 1.3f,
                pipeHz = 230f, pipeQ = 0.9f,
                ringHz = 3200f, ringAmount = 0.1f,
                intakeNoise = 0.4f,
                exhaustGrit = 0.16f,
                decelPopProbability = 0.5f,
                compressionTone = 0.55f
            ),
            EngineProfile(
                key = "V8_MUSCLE",
                displayName = "V8 muscle",
                kind = Kind.ICE,
                // Recorded: an AC Cobra 427 (BigSoundBank s0534)
                grainAsset = "grain_v8_muscle",
                idleRpm = 700, maxRpm = 3200,
                cylinderCount = 8,
                firingAngles = floatArrayOf(0f, 90f, 180f, 270f, 360f, 450f, 540f, 630f),
                // Cross-plane, firing order 1-8-4-3-6-5-7-2: the banks go L R R L R L L R
                banks = intArrayOf(0, 1, 1, 0, 1, 0, 0, 1),
                pipeHz = 200f, pipeQ = 1.1f,
                intakeNoise = 0.35f,
                exhaustGrit = 0.28f,
                decelPopProbability = 0.55f,
                compressionTone = 0.7f
            ),
            EngineProfile(
                key = "V12_ITALIAN",
                displayName = "V12 (Italian)",
                kind = Kind.ICE,
                // Recorded: an Aston Martin Vanquish V12 (Internet Archive, CC0)
                grainAsset = "grain_v12_italian",
                idleRpm = 900, maxRpm = 4000,
                cylinderCount = 12,
                firingAngles = FloatArray(12) { i -> i * 60f },
                pipeHz = 240f, pipeQ = 0.8f,
                ringHz = 3000f, ringAmount = 0.15f,
                intakeNoise = 0.3f,
                exhaustGrit = 0.14f,
                decelPopProbability = 0.35f,
                compressionTone = 0.55f
            ),
            EngineProfile(
                key = "V16_LUXURY",
                displayName = "V16 (luxury)",
                kind = Kind.ICE,
                idleRpm = 550, maxRpm = 5800,
                cylinderCount = 16,
                firingAngles = FloatArray(16) { i -> i * 45f },
                // Big pipes, soft pulses: a deep wave of an engine, nothing sharp in it
                pipeHz = 160f, pipeQ = 0.7f,
                gainTrim = 1.3f,
                intakeNoise = 0.15f,
                exhaustGrit = 0.1f,
                decelPopProbability = 0.2f,
                compressionTone = 0.4f
            ),
            // --- Extra ICE flavors ---
            EngineProfile(
                key = "BOXER_6",
                displayName = "Boxer 6",
                kind = Kind.ICE,
                idleRpm = 850, maxRpm = 7800,
                cylinderCount = 6,
                // Flat-6 firing pattern (Porsche-ish): even 120 degree steps in the 720 degree cycle
                firingAngles = floatArrayOf(0f, 120f, 240f, 360f, 480f, 600f),
                gainTrim = 1.3f,
                pipeHz = 210f, pipeQ = 1f,
                ringHz = 2400f, ringAmount = 0.12f,
                intakeNoise = 0.35f,
                exhaustGrit = 0.18f,
                decelPopProbability = 0.45f,
                compressionTone = 0.5f
            ),
            EngineProfile(
                key = "INLINE_6_SILKY",
                displayName = "Inline-6 (silky)",
                kind = Kind.ICE,
                idleRpm = 700, maxRpm = 6500,
                cylinderCount = 6,
                firingAngles = floatArrayOf(0f, 120f, 240f, 360f, 480f, 600f),
                gainTrim = 1.3f,
                // A longer, cleaner pipe than the boxer's rasp: the note sings more than it barks
                pipeHz = 220f, pipeQ = 1.3f,
                ringHz = 2000f, ringAmount = 0.06f,
                intakeNoise = 0.3f,
                exhaustGrit = 0.12f,
                decelPopProbability = 0.25f,
                compressionTone = 0.5f
            ),
            EngineProfile(
                key = "TRACTOR",
                displayName = "Tractor",
                kind = Kind.ICE, gearless = true,
                // Recorded: an old tractor idling (BigSoundBank)
                grainAsset = "grain_tractor",
                idleRpm = 450, maxRpm = 1500,
                cylinderCount = 1,
                firingAngles = floatArrayOf(0f),
                pipeHz = 230f, pipeQ = 1.4f,
                gainTrim = 3.0f,
                intakeNoise = 0.25f,
                pulseMs = 6f,
                exhaustGrit = 0.35f,
                decelPopProbability = 0.55f,
                compressionTone = 0.6f
            ),
            EngineProfile(
                key = "STEAM_LOCO",
                displayName = "Steam locomotive",
                kind = Kind.ICE, gearless = true,
                // Recorded: a locomotive under way (BigSoundBank). Driving wheel
                // turns: four chuffs each, a crawl to a run
                grainAsset = "grain_steam_loco",
                idleRpm = 50, maxRpm = 220,
                cylinderCount = 2,
                // Two cylinders 180 degrees apart, double acting: four chuffs per turn
                firingAngles = floatArrayOf(0f, 180f),
                twoStroke = true,
                pipeHz = 320f, pipeQ = 0.5f,
                intakeNoise = 0.2f,
                pulseMs = 160f,
                gainTrim = 5f,
                exhaustGrit = 0.7f,
                decelPopProbability = 0.0f,
                compressionTone = 0.75f
            ),
            // --- Synth / futuristic (gearless) ---
            EngineProfile(
                key = "TRON_WHINE",
                displayName = "Tron whine",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 600, maxRpm = 14000,
                synthBaseHz = 180f, synthMaxHz = 1500f,
                voice = SynthVoice.SQUARE,
                synthFmDepth = 0.15f,
                exhaustGrit = 0.05f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "POD_RACER",
                displayName = "Pod racer",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 500, maxRpm = 10000,
                // Two big turbines side by side, a shade apart in speed, so they beat and throb
                synthBaseHz = 140f, synthMaxHz = 800f,
                voice = SynthVoice.TURBINE,
                synthFmDepth = 0.6f,
                synthWhine = 0.6f,
                exhaustGrit = 0.65f,
                decelPopProbability = 0.3f
            ),
            EngineProfile(
                key = "LIGHTSABER", preview = true,
                displayName = "Lightsaber",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 300, maxRpm = 1800,
                synthBaseHz = 190f, synthMaxHz = 380f,
                voice = SynthVoice.HUM,
                synthFmDepth = 0.75f,
                exhaustGrit = 0.02f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "JET_TURBINE",
                displayName = "Jet turbine",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 1500, maxRpm = 18000,
                synthBaseHz = 300f, synthMaxHz = 2000f,
                voice = SynthVoice.TURBINE,
                synthFmDepth = 0.08f,
                exhaustGrit = 0.55f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "UFO_HOVER",
                displayName = "UFO hover",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 700, maxRpm = 3200,
                synthBaseHz = 220f, synthMaxHz = 620f,
                voice = SynthVoice.HUM,
                synthFmDepth = 1.0f,
                exhaustGrit = 0.1f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "F1_ELECTRIC", preview = true,
                displayName = "F1 electric whine",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 800, maxRpm = 20000,
                synthBaseHz = 220f, synthMaxHz = 1900f,
                voice = SynthVoice.ELECTRIC,
                synthFmDepth = 0.04f,
                exhaustGrit = 0.0f,
                decelPopProbability = 0.0f
            ),
            EngineProfile(
                key = "HOVER_BIKE",
                displayName = "Hover bike",
                kind = Kind.SYNTH, gearless = true,
                idleRpm = 500, maxRpm = 7000,
                // Ducted fans: mostly air, a low fan note under it, no scream
                synthBaseHz = 170f, synthMaxHz = 650f,
                voice = SynthVoice.TURBINE,
                synthFmDepth = 0.1f,
                synthWhine = 0.45f,
                exhaustGrit = 0.4f,
                decelPopProbability = 0.0f
            ),
            // --- Recorded engines without a synthesised twin (all CC0, see docs/SFX_SOURCES.md) ---
            EngineProfile(
                key = "SAMPLED_DIESEL_IVECO",
                displayName = "Diesel truck",
                kind = Kind.ICE,
                // Recorded: an Iveco Daily 3.0 diesel, small and big accelerations (BigSoundBank s1146, s1147)
                grainAsset = "grain_diesel_truck",
                idleRpm = 800, maxRpm = 3600,
                cylinderCount = 4,
                firingAngles = floatArrayOf(0f, 180f, 360f, 540f),
                pipeHz = 180f, pipeQ = 0.8f,
                intakeNoise = 0.3f, exhaustGrit = 0.3f,
                decelPopProbability = 0.1f, compressionTone = 0.8f
            ),
            EngineProfile(
                key = "SAMPLED_BROKEN_EXHAUST",
                displayName = "Broken exhaust",
                kind = Kind.ICE,
                // Recorded: a car with a holed muffler (BigSoundBank)
                grainAsset = "grain_broken_exhaust",
                idleRpm = 800, maxRpm = 3000,
                cylinderCount = 4,
                firingAngles = floatArrayOf(0f, 180f, 360f, 540f),
                pipeHz = 190f, pipeQ = 0.6f,
                intakeNoise = 0.3f, exhaustGrit = 0.5f,
                decelPopProbability = 0.6f, compressionTone = 0.8f
            ),
            // Hidden: thin recordings, kept for riders who picked them before.
            EngineProfile(
                key = "SAMPLED_MOTORCYCLE", preview = true,
                displayName = "Motorcycle",
                kind = Kind.ICE,
                grainAsset = "grain_motorcycle",
                idleRpm = 1000, maxRpm = 3500,
                cylinderCount = 2, firingAngles = floatArrayOf(0f, 360f),
                pipeHz = 220f, pipeQ = 1f, gainTrim = 1.7f
            ),
            EngineProfile(
                key = "SAMPLED_LAWNMOWER", preview = true,
                displayName = "Lawn mower",
                kind = Kind.ICE, gearless = true,
                grainAsset = "grain_lawnmower",
                idleRpm = 2300, maxRpm = 4600,
                cylinderCount = 1, firingAngles = floatArrayOf(0f),
                pipeHz = 260f, pipeQ = 0.8f, gainTrim = 2.2f
            ),
            EngineProfile(
                key = "SAMPLED_BIG_DIESEL", preview = true,
                displayName = "Big diesel",
                kind = Kind.ICE,
                grainAsset = "grain_big_diesel",
                idleRpm = 600, maxRpm = 1800,
                cylinderCount = 6, firingAngles = FloatArray(6) { i -> i * 120f },
                pipeHz = 150f, pipeQ = 0.7f, gainTrim = 1.3f
            ),
            // Hidden: the old "sampled" keys, each the same recording as a listed engine,
            // so a rider who picked one keeps their sound.
            EngineProfile(key = "SAMPLED_V8_COBRA", preview = true, displayName = "V8 Cobra", kind = Kind.ICE,
                grainAsset = "grain_v8_muscle", idleRpm = 700, maxRpm = 3200,
                cylinderCount = 8, firingAngles = FloatArray(8) { i -> i * 90f }, banks = intArrayOf(0, 1, 1, 0, 1, 0, 0, 1), pipeHz = 200f),
            EngineProfile(key = "SAMPLED_VTWIN_DUCATI", preview = true, displayName = "V-twin Ducati", kind = Kind.ICE,
                grainAsset = "grain_v_twin", idleRpm = 1000, maxRpm = 6500,
                cylinderCount = 2, firingAngles = floatArrayOf(0f, 315f), pipeHz = 210f, gainTrim = 1.7f),
            EngineProfile(key = "SAMPLED_CITY_CAR", preview = true, displayName = "City car", kind = Kind.ICE,
                grainAsset = "grain_inline_4", idleRpm = 900, maxRpm = 4000,
                cylinderCount = 4, firingAngles = floatArrayOf(0f, 180f, 360f, 540f), pipeHz = 230f),
            EngineProfile(key = "SAMPLED_TRACTOR", preview = true, displayName = "Tractor", kind = Kind.ICE, gearless = true,
                grainAsset = "grain_tractor", idleRpm = 450, maxRpm = 1500,
                cylinderCount = 1, firingAngles = floatArrayOf(0f), pipeHz = 230f, pulseMs = 6f, gainTrim = 3f),
            EngineProfile(key = "SAMPLED_STEAM_LOCO", preview = true, displayName = "Steam locomotive", kind = Kind.ICE, gearless = true,
                grainAsset = "grain_steam_loco", idleRpm = 50, maxRpm = 220,
                cylinderCount = 2, firingAngles = floatArrayOf(0f, 180f), twoStroke = true, pipeHz = 320f, pulseMs = 160f, gainTrim = 5f),
            EngineProfile(key = "SAMPLED_ASTON_MARTIN", preview = true, displayName = "Aston Martin", kind = Kind.ICE,
                grainAsset = "grain_v12_italian", idleRpm = 900, maxRpm = 4000,
                cylinderCount = 12, firingAngles = FloatArray(12) { i -> i * 60f }, pipeHz = 240f),
            EngineProfile(key = "SAMPLED_QUAD_ATV", preview = true, displayName = "Quad ATV", kind = Kind.ICE, gearless = true,
                grainAsset = "grain_quad_bike", idleRpm = 1000, maxRpm = 3600,
                cylinderCount = 1, firingAngles = floatArrayOf(0f), pipeHz = 170f, pulseMs = 4.5f, gainTrim = 2.5f)
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
