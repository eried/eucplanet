package com.eried.eucplanet.audio

/**
 * A clipped window inside a raw resource. The player seeks to [startMs] and
 * loops/stops at [endMs]. For looped sections (idle_loop, rev_loop) the player
 * seeks back to startMs when reaching endMs; for one-shots (startup, decel,
 * shutdown, pops) the player stops at endMs.
 */
data class SampleSection(
    /** res/raw filename without extension (any decoder-supported format works, ogg/mp3/wav/flac). */
    val rawAsset: String,
    val startMs: Int,
    val endMs: Int,
) {
    val durationMs: Int get() = (endMs - startMs).coerceAtLeast(0)
}

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
 * Two flavors:
 *  - [Kind.ICE], internal combustion. Each cylinder fires at its angle of the
 *    crank cycle and sends an exhaust pulse into a pipe: a few resonators tuned
 *    by [pipeHz] that give the engine its body, intake and valve noise rising
 *    with load, an optional ring ([ringHz]) for 2-strokes and screamers.
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
     * Resource basename for sample-based rendering, looked up under `res/raw/`.
     *
     * - `${sampleAssetBase}_idle` is the looped idle sample (required)
     * - `${sampleAssetBase}_rev`  is the optional mid/high-RPM loop (crossfaded by load)
     *
     * When set, the engine is rendered by playing back and pitch-shifting these
     * samples instead of the procedural [EngineSynth]. Null = procedural.
     */
    val sampleAssetBase: String? = null,
    // --- Per-engine feature support ---
    // ICE / SYNTH profiles use the in-house DSP so every effect is "free" and these default to true.
    // Sampled profiles can only do what MediaPlayer + SoundPool sidecars give us:
    //  - muffler LPF: only the DSP path has a filter, set false on sampled profiles
    //  - pops / brake whine: only available if a SoundPool side-channel asset is wired
    //    (popSampleAsset / brakeWhineSampleAsset). Until the sidecars ship, sampled
    //    profiles set these false so the UI can grey-out the controls per engine.
    /** Whether the muffler/exhaust LPF row applies to this engine. */
    val supportsMuffler: Boolean = true,
    /** Whether the decel-pops/backfire row applies to this engine. */
    val supportsPops: Boolean = true,
    /** Whether the engine-brake whine row applies to this engine. */
    val supportsBrakeWhine: Boolean = true,
    /** Optional res/raw name (without extension) for a sampled pop SFX, played via SoundPool. */
    val popSampleAsset: String? = null,
    /** Optional res/raw name (without extension) for a sampled brake-whine loop. */
    val brakeWhineSampleAsset: String? = null,
    /**
     * Multi-section composition. When set, the engine is rendered by
     * [com.eried.eucplanet.audio.CompositionEnginePlayer] (cross-fading
     * idle_loop ↔ rev_loop, with one-shot startup / decel / shutdown).
     * Section keys: "startup", "idle_loop", "rev_up", "rev_loop", "decel", "shutdown".
     */
    val sampleSections: Map<String, SampleSection>? = null,
    /** Optional pop SFX variants, picked at random when a pop fires. Supersedes [popSampleAsset]. */
    val popSections: List<SampleSection>? = null,
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
                idleRpm = 850, maxRpm = 6200,
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
                idleRpm = 950, maxRpm = 6500,
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
                idleRpm = 1100, maxRpm = 11000,
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
                idleRpm = 700, maxRpm = 7000,
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
                idleRpm = 800, maxRpm = 7500,
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
                idleRpm = 450, maxRpm = 2400,
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
                // Driving wheel turns: four chuffs each, a crawl to a run
                idleRpm = 60, maxRpm = 420,
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
            // --- Sampled (real recordings, all CC0 from BigSoundBank, see Credits) ---
            // For sampled engines the procedural [kind] flags below are unused; the SampledEnginePlayer
            // handles render. Idle/max RPM still drive the playback-speed mapping.
            EngineProfile(
                key = "SAMPLED_V8_COBRA",
                displayName = "V8 (Cobra, sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 700, maxRpm = 6500,
                sampleAssetBase = "engine_v8_cobra",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_v8_cobra",     92,  1805),
                    "idle_loop" to SampleSection("engine_v8_cobra",  13129, 19688),
                    "rev_up"    to SampleSection("engine_v8_cobra",   9949, 11120),
                    "rev_loop"  to SampleSection("engine_v8_cobra",   3313,  6703),
                    "decel"     to SampleSection("engine_v8_cobra",  11166, 12429),
                    "shutdown"  to SampleSection("engine_v8_cobra",  21677, 25440)
                ),
                popSections = listOf(SampleSection("engine_v8_cobra", 20143, 20527))
            ),
            EngineProfile(
                key = "SAMPLED_VTWIN_DUCATI", preview = true,
                displayName = "V-twin (Ducati, sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 950, maxRpm = 9000,
                sampleAssetBase = "engine_vtwin_ducati",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_vtwin_ducati",   476,  3096),
                    "idle_loop" to SampleSection("engine_vtwin_ducati", 10346, 13347),
                    "rev_up"    to SampleSection("engine_vtwin_ducati", 30830, 33412),
                    "rev_loop"  to SampleSection("engine_vtwin_ducati",  3658,  6393),
                    "decel"     to SampleSection("engine_vtwin_ducati", 33955, 35984),
                    "shutdown"  to SampleSection("engine_vtwin_ducati", 36108, 41433)
                ),
                popSections = listOf(SampleSection("src_bsb_0281", 10343, 10667))
            ),
            EngineProfile(
                key = "SAMPLED_DIESEL_IVECO", preview = true,
                displayName = "Diesel truck (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 800, maxRpm = 3200,
                sampleAssetBase = "engine_diesel_iveco",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_diesel_iveco",     0,  1584),
                    "idle_loop" to SampleSection("src_bsb_1146",         8008, 10057),
                    "rev_up"    to SampleSection("src_bsb_1146",        10467, 12702),
                    "rev_loop"  to SampleSection("src_bsb_1147",         3823,  6423),
                    "decel"     to SampleSection("src_bsb_1147",        16105, 17829),
                    "shutdown"  to SampleSection("engine_diesel_iveco", 32984, 35278)
                ),
                popSections = listOf(SampleSection("src_bsb_1146", 21077, 21616))
            ),
            EngineProfile(
                key = "SAMPLED_MOTORCYCLE", preview = true,
                displayName = "Motorcycle (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 1100, maxRpm = 10500,
                sampleAssetBase = "engine_motorcycle",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_motorcycle",              147,  1272),
                    "idle_loop" to SampleSection("src_fs_119774_moto_start",      2860,  3603),
                    "rev_up"    to SampleSection("engine_motorcycle",             6653,  7419),
                    "rev_loop"  to SampleSection("engine_motorcycle",            10125, 10896),
                    "decel"     to SampleSection("src_fs_119774_moto_start",      4257,  5156),
                    "shutdown"  to SampleSection("engine_motorcycle",            11923, 13558)
                ),
                popSections = listOf(SampleSection("src_fs_119774_moto_start", 857, 956))
            ),
            EngineProfile(
                key = "SAMPLED_CITY_CAR", preview = true,
                displayName = "City car (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 850, maxRpm = 6000,
                sampleAssetBase = "engine_citycar_saxo",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_citycar_saxo",     0,  1420),
                    "idle_loop" to SampleSection("src_bsb_0187",        22388, 26996),
                    "rev_up"    to SampleSection("engine_citycar_saxo",  4363,  5872),
                    "rev_loop"  to SampleSection("engine_citycar_saxo",  6447,  8024),
                    "decel"     to SampleSection("src_bsb_0966",         4174,  8028),
                    "shutdown"  to SampleSection("src_bsb_0187",        32697, 41169)
                )
            ),
            EngineProfile(
                key = "SAMPLED_HELICOPTER", preview = true,
                displayName = "Helicopter (sampled)",
                kind = Kind.ICE, gearless = true,
                // Helicopter rotor "RPM" is metaphorical here, drives playback speed mapping
                idleRpm = 250, maxRpm = 1100,
                sampleAssetBase = "engine_helicopter",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_fs_559340_heli_long",      0,   7833),
                    "idle_loop" to SampleSection("src_fs_559340_heli_long",  27395,  38701),
                    "rev_up"    to SampleSection("src_fs_559340_heli_long",  53325,  55987),
                    "rev_loop"  to SampleSection("src_fs_559340_heli_long",  59498,  61852),
                    "decel"     to SampleSection("src_fs_559340_heli_long",  61968,  67717),
                    "shutdown"  to SampleSection("src_fs_559340_heli_long", 119768, 141723)
                ),
                popSections = listOf(SampleSection("src_fs_559340_heli_long", 79023, 80296))
            ),
            EngineProfile(
                key = "SAMPLED_TRACTOR", preview = true,
                displayName = "Tractor (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 600, maxRpm = 2600,
                sampleAssetBase = "engine_tractor",
                supportsMuffler = false, supportsPops = false, supportsBrakeWhine = false,
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_tractor",                  0,  1776),
                    "idle_loop" to SampleSection("src_fs_256808_tractor_clean", 6436,  7944),
                    "rev_loop"  to SampleSection("engine_tractor",               5921,  9672),
                    "shutdown"  to SampleSection("engine_tractor",              41695, 46648)
                )
            ),
            EngineProfile(
                key = "SAMPLED_LAWNMOWER", preview = true,
                displayName = "Lawn mower (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 1800, maxRpm = 3600,
                sampleAssetBase = "engine_lawnmower",
                supportsMuffler = false, supportsPops = false, supportsBrakeWhine = false,
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_bsb_0763",         0,  2498),
                    "rev_up"    to SampleSection("engine_lawnmower",  1174,  2771),
                    "idle_loop" to SampleSection("src_bsb_0763",      7073, 12346)
                    // No decel / rev_loop / shutdown, omitted per user inventory.
                )
            ),
            EngineProfile(
                key = "SAMPLED_STEAM_LOCO", preview = true,
                displayName = "Steam locomotive (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 200, maxRpm = 900,
                sampleAssetBase = "engine_steam_loco",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_fs_784612_bulleid",       0,  12161),
                    "idle_loop" to SampleSection("engine_steam_loco",        9042,  17316),
                    "rev_up"    to SampleSection("engine_steam_loco",       82374,  93361),
                    "rev_loop"  to SampleSection("engine_steam_loco",       40147,  54208),
                    "decel"     to SampleSection("engine_steam_loco",       67093,  78260),
                    "shutdown"  to SampleSection("src_fs_784612_bulleid", 108707, 112272)
                ),
                popSections = listOf(SampleSection("src_fs_784612_bulleid", 76172, 78541))
            ),
            EngineProfile(
                key = "SAMPLED_CAR_CRUISE", preview = true,
                displayName = "Car cruise (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 1000, maxRpm = 5500,
                sampleAssetBase = "engine_car_cruise",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_bsb_0872",     6572, 10439),
                    "idle_loop" to SampleSection("src_bsb_0872",    19678, 23269),
                    "rev_up"    to SampleSection("src_bsb_0291",     1040,  1647),
                    "rev_loop"  to SampleSection("src_bsb_0291",     3216,  4146),
                    "decel"     to SampleSection("src_bsb_0291",     4739,  5647),
                    "shutdown"  to SampleSection("src_bsb_0966",    46435, 50020)
                ),
                popSections = listOf(SampleSection("engine_car_cruise", 48719, 51509))
            ),
            EngineProfile(
                key = "SAMPLED_ASTON_MARTIN", preview = true,
                displayName = "Aston Martin (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 900, maxRpm = 7500,
                sampleAssetBase = "engine_aston_martin",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                // Multi-section composition picked in deletelater.html: Vanquish full life
                // cycle from src_ia_vanquish.mp3 + rev_up from the original aston_martin clip
                // + 3 pop variants from src_ia_v12vantage.mp3.
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_ia_vanquish",     713, 3455),
                    "idle_loop" to SampleSection("src_ia_vanquish",    4012, 10662),
                    "rev_up"    to SampleSection("engine_aston_martin",   0, 2309),
                    "rev_loop"  to SampleSection("src_ia_vanquish",   16592, 18134),
                    "decel"     to SampleSection("src_ia_vanquish",   11748, 13161),
                    "shutdown"  to SampleSection("src_ia_vanquish",   18282, 19924)
                ),
                popSections = listOf(
                    SampleSection("src_ia_v12vantage", 4457, 7206),  // pop_a
                    SampleSection("src_ia_v12vantage", 7624, 9699),  // pop_b
                    SampleSection("src_ia_v12vantage", 1862, 3761)   // pop_c
                )
            ),
            EngineProfile(
                key = "SAMPLED_BIG_DIESEL", preview = true,
                displayName = "Big diesel (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 600, maxRpm = 2800,
                sampleAssetBase = "engine_big_diesel",
                supportsMuffler = false, supportsBrakeWhine = false,
                // User layered a "pop_a" clatter from src_bsb_1146 so flip pops on.
                // Uses sfx_pop_snap (BSB bang-snaps, the lighter of our two pop SFX) since
                // diesel "pops" are mechanical clatter / turbo wastegate, not spark backfire.
                supportsPops = true, popSampleAsset = "sfx_pop_snap",
                // Multi-section composition. Where two regions share a type, the LATER
                // entry in the user's export wins (the user adds regions iteratively as
                // they refine their pick).
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_bsb_1146",     0, 2394),
                    "idle_loop" to SampleSection("src_bsb_1146", 22098, 23888),
                    "rev_up"    to SampleSection("src_bsb_1146", 10237, 11682),
                    "rev_loop"  to SampleSection("src_bsb_1147",  8433, 10980),
                    "decel"     to SampleSection("src_bsb_1147", 16454, 18697),
                    "shutdown"  to SampleSection("src_bsb_1146", 32536, 35771)
                ),
                popSections = listOf(
                    SampleSection("src_bsb_1146", 21106, 21674)   // pop_a, mechanical clatter
                )
            ),
            EngineProfile(
                key = "SAMPLED_BROKEN_EXHAUST",
                displayName = "Broken exhaust (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 800, maxRpm = 5000,
                sampleAssetBase = "engine_damaged_muffler",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                sampleSections = mapOf(
                    "startup"   to SampleSection("engine_damaged_muffler",   391,  3277),
                    "idle_loop" to SampleSection("engine_damaged_muffler", 41081, 44573),
                    "rev_up"    to SampleSection("engine_damaged_muffler",  5380,  8324),
                    "rev_loop"  to SampleSection("engine_damaged_muffler", 15709, 25323),
                    "decel"     to SampleSection("engine_damaged_muffler", 30674, 33598),
                    "shutdown"  to SampleSection("engine_damaged_muffler", 46793, 48671)
                ),
                popSections = listOf(SampleSection("engine_damaged_muffler", 34606, 36748))
            ),
            EngineProfile(
                key = "SAMPLED_QUAD_ATV", preview = true,
                displayName = "Quad ATV (sampled)",
                kind = Kind.ICE, gearless = true,
                idleRpm = 900, maxRpm = 6500,
                sampleAssetBase = "engine_quad_atv",
                supportsMuffler = false, supportsBrakeWhine = false,
                supportsPops = true, popSampleAsset = "sfx_pop_crack",
                sampleSections = mapOf(
                    "startup"   to SampleSection("src_bsb_0712",     0,  1449),
                    "idle_loop" to SampleSection("src_bsb_0712",  4354,  5939),
                    "rev_up"    to SampleSection("src_bsb_0712",  2585,  3707),
                    "rev_loop"  to SampleSection("src_bsb_0712", 14081, 16809),
                    "decel"     to SampleSection("src_bsb_0712",  6789,  8796),
                    "shutdown"  to SampleSection("src_bsb_0712", 30230, 33312)
                )
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
