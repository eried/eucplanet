package com.eried.eucplanet.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * DSP core. Owned by the audio thread, not thread-safe.
 *
 * Renders mono Float32 PCM at [sampleRate] driven by an [EngineParams] snapshot.
 * State (crank, resonators, envelopes) carries across [render] calls so the
 * audio is continuous across buffers. Everything lands where a phone speaker
 * plays, roughly 150 Hz to 6 kHz: the engine's note is carried by the firing
 * rate shaping the pipe's ring, not by a sub-bass fundamental nobody hears.
 *
 * Three paths:
 *  - Recorded ([EngineProfile.grainAsset]): grains of a real engine from a
 *    [GrainBank], laid down one pattern period apart. The wheel sets how close
 *    together they come, so the engine fires faster or slower while its pipes
 *    keep their real pitch, like a real engine does. Nothing is synthesised.
 *  - ICE: each cylinder fires at its angle of the crank cycle ([FiringSchedule])
 *    and drops an exhaust pulse (a thump plus a crack of noise) into a pipe of
 *    three resonators tuned by the profile, with intake roar and valvetrain
 *    noise rising on load, a high ring for 2-strokes and screamers, crackle on
 *    the overrun, pops on the throttle chop. For engines nobody has recorded.
 *  - SYNTH: a [SynthVoice] (bandlimited saw or square, hum partials, a
 *    turbine of shaped noise and whine, an electric motor) riding on RPM.
 *
 * [grainLoader] opens a bank by its asset name (Android resources on the
 * phone, a folder in tests). Banks are kept once loaded.
 */
class EngineSynth(
    private val sampleRate: Int = 44100,
    private val grainLoader: ((String) -> GrainBank?)? = null,
) {

    private val dt: Float = 1f / sampleRate

    // --- Recorded engine state ---
    private val banks = HashMap<String, GrainBank?>()
    private var bank: GrainBank? = null
    private var bankKey: String? = null
    // The grains in the air right now: a pool of arrays being summed, each at a gain.
    private val gBuf = arrayOfNulls<FloatArray>(GRAINS)
    private val gPos = IntArray(GRAINS)
    private val gGain = FloatArray(GRAINS)
    private var hopLeft = 0f                        // samples until the next grain is laid
    // Two streams: the band just below the target rate and the band just above, crossfaded.
    private val streamBand = IntArray(2) { -1 }
    private val streamRun = arrayOfNulls<Array<FloatArray>>(2)
    private val streamIdx = IntArray(2)
    private var grainAge = 0f                       // seconds since this engine came on
    private var startupSeconds = 0f
    private val whistle = Whistle()
    private var blowOff = 0f                        // the boiler hissing at a standstill, 0..1

    // --- ICE state ---
    private var schedule: FiringSchedule? = null
    private var scheduleKey: String? = null
    private var cylGain: FloatArray = FloatArray(0)   // each cylinder a touch louder or quieter
    private var cylLen: FloatArray = FloatArray(0)    // and its own exhaust length, so its pulse bleeds off differently
    private var cylDelay: IntArray = IntArray(0)      // samples from the firing to the pulse reaching the collector
    private var cylBank: IntArray = IntArray(0)
    private var combustion: Float = 0f               // slow cycle-to-cycle drift of the burn
    // Pulses on their way down unequal header pipes: amplitude, bank, samples to go.
    private val pendAmp = FloatArray(PENDING)
    private val pendBank = IntArray(PENDING)
    private val pendLeft = IntArray(PENDING)
    // The exhaust pulse per bank as three decays: the pressure (slow), the front that
    // carves its rise (fast), the undershoot behind it (slower, negative). Each firing
    // adds to them, so pulses pile up at high revs instead of being squeezed shorter.
    private val pressure = FloatArray(2)
    private val front = FloatArray(2)
    private val wake = FloatArray(2)
    private var crackEnv: Float = 0f                 // the noise burst on the front of a pulse
    private var tickAge: Float = 1e9f
    private val pipeA = Pipe(sampleRate)
    private val pipeB = Pipe(sampleRate)
    private val cross1 = Resonator()
    private val cross2 = Resonator()
    private val body = Resonator()
    private val bark = Resonator()
    private val ring = Resonator()
    private val intake = Resonator()
    private val tick = Resonator()
    private val crackleBand = Resonator()
    private var brakePhase: Double = 0.0
    private var gearPhase: Double = 0.0

    // --- SYNTH state ---
    private var oscPhase: Double = 0.0
    private var osc2Phase: Double = 0.0
    private var subPhase: Double = 0.0
    private var lfoPhase: Double = 0.0
    private var lfo2Phase: Double = 0.0
    private var spooledNorm: Float = -1f
    // BUBBLE voice: where we are in the run of blips, and the blip in the air.
    private var blipPhase: Float = 1f
    private var blipT: Float = 1e9f
    private var blipLen: Float = 0.1f
    private var blipHz: Float = 500f
    private val rumble = Resonator()
    private val roar = Resonator()
    private val wind = Resonator()
    private val whinePhases = DoubleArray(5)

    // --- shared ---
    private val pink = PinkNoise()
    private var lpf1: Float = 0f
    private var lpf2: Float = 0f
    private var hpf1In: Float = 0f
    private var hpf1Out: Float = 0f
    private var hpf2In: Float = 0f
    private var hpf2Out: Float = 0f
    private val popQueue = ArrayDeque<Pop>(4)
    private var rngState: Int = 0x1A2B3C4D

    private fun nextNoise(): Float {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x.toFloat() / Int.MAX_VALUE.toFloat()
    }

    /** Render [count] samples into [out] from [outOffset]. Returns the peak, for diagnostics. */
    fun render(params: EngineParams, out: FloatArray, outOffset: Int, count: Int): Float {
        val profile = params.profile
        // A synthesised engine pops with a burst of noise through its pipe; a recorded one
        // pops with its own recording, or a bang the grain player shapes itself.
        if (profile.grainAsset == null) repeat(params.pendingPops) { popQueue.addLast(Pop(remaining = (sampleRate * 0.16f).toInt())) }

        val effRpm = (params.rpm + params.revBump).coerceAtLeast(0f)
        val rpmRange = (profile.maxRpm - profile.idleRpm).coerceAtLeast(1)
        val rpmNorm = ((effRpm - profile.idleRpm) / rpmRange).coerceIn(0f, 1f)
        val loadGain = 0.7f + 0.3f * (0.5f * rpmNorm + 0.5f * params.load)
        val masterGain = params.masterGain * params.idleAmount * loadGain * profile.gainTrim * OUT_LEVEL

        // Muffler: two one-pole low-passes in a row. Open pipes 7 kHz, muffled 900 Hz.
        val open = params.mufflerOpenness.coerceIn(0f, 1f)
        val cutoffHz = 900f + (7000f - 900f) * open * open
        val lpAlpha = onePoleAlpha(cutoffHz)
        // Sub cut: two one-pole high-passes at 100 Hz. The speaker plays none of it anyway.
        val hpCoef = 1f - onePoleAlpha(100f)

        var peak = 0f
        if (profile.grainAsset != null) {
            // Throttle shut at speed: no fuel, so the exhaust goes quiet and dull and what
            // is left is the engine turning over. The recording was made under power.
            val overrun = (1f - params.load.coerceIn(0f, 1f)) * rpmNorm
            val lpOver = onePoleAlpha(cutoffHz * (1f - 0.65f * overrun))
            peak = renderGrains(profile, params, effRpm, rpmNorm, overrun, masterGain, lpOver, hpCoef, out, outOffset, count)
        } else if (profile.kind == EngineProfile.Kind.SYNTH) {
            peak = renderSynth(profile, params, rpmNorm, masterGain, lpAlpha, hpCoef, out, outOffset, count)
        } else {
            peak = renderIce(profile, params, effRpm, rpmNorm, masterGain, lpAlpha, hpCoef, out, outOffset, count)
        }
        return peak
    }

    /** Opens the recorded engine's bank before the audio thread needs it. Safe to call for any profile. */
    fun preload(profile: EngineProfile) {
        val asset = profile.grainAsset ?: return
        if (!banks.containsKey(asset)) banks[asset] = grainLoader?.invoke(asset)
    }

    /** True when the profile is a recorded engine whose bank is available. */
    fun hasBank(profile: EngineProfile): Boolean {
        val asset = profile.grainAsset ?: return false
        preload(profile)
        return banks[asset] != null
    }

    private fun renderGrains(
        profile: EngineProfile, params: EngineParams, effRpm: Float, rpmNorm: Float, overrun: Float,
        masterGain: Float, lpAlpha: Float, hpCoef: Float, out: FloatArray, outOffset: Int, count: Int
    ): Float {
        val asset = profile.grainAsset!!
        if (bankKey != profile.key) {
            preload(profile)
            bank = banks[asset]
            bankKey = profile.key
            gBuf.fill(null); hopLeft = 0f
            streamBand.fill(-1); streamRun.fill(null)
            grainAge = 0f
            pipeA.reset(); body.reset(); wind.reset(); popQueue.clear()
            blowOff = 0f
            val b = bank
            startupSeconds = 0f
            if (b != null && b.startup.isNotEmpty()) {
                // The engine starts: the recording's own start, the running grains coming in under it.
                lay(b.startup, 1f)
                startupSeconds = b.startup.size.toFloat() / sampleRate
            }
            // The locomotive announces itself.
            if (profile.whistle) whistle.blow(1.2f, after = 0.4f)
        }
        val b = bank
        if (b == null) {
            // No bank: the ICE model stands in, so a missing asset is never silence.
            return renderIce(profile, params, effRpm, rpmNorm, masterGain, lpAlpha, hpCoef, out, outOffset, count)
        }
        val load = params.load
        // The recording's slowest band is this engine at idle; the wheel scales it from there.
        val f = (b.lowestHz * effRpm / profile.idleRpm.coerceAtLeast(1)).coerceAtLeast(b.lowestHz * 0.5f)
        val hop = sampleRate / f
        // The bands either side of the target rate, and how far between them it sits.
        val bands = b.bands
        var lo = 0
        while (lo + 1 < bands.size && bands[lo + 1].hz <= f) lo++
        val hi = if (lo + 1 < bands.size && f > bands[lo].hz) lo + 1 else lo
        val wHi = if (hi == lo) 0f else (ln(f / bands[lo].hz) / ln(bands[hi].hz / bands[lo].hz)).coerceIn(0f, 1f)
        val w0 = kotlin.math.sqrt(1f - wHi)
        val w1 = kotlin.math.sqrt(wHi)
        if (streamBand[0] != lo) { streamBand[0] = lo; streamRun[0] = null }
        if (streamBand[1] != hi) { streamBand[1] = hi; streamRun[1] = null }
        // Grains laid closer than they were recorded pile up: level them, but let the
        // engine grow louder with the revs as a real one does.
        val dens0 = kotlin.math.sqrt(bands[lo].hz / f).coerceIn(0.35f, 1.4f)
        val dens1 = kotlin.math.sqrt(bands[hi].hz / f).coerceIn(0.35f, 1.4f)
        val loadGain = (0.8f + 0.2f * load) * (1f - 0.5f * overrun)
        val brakeAmt = params.engineBrakeAmount
        // On the overrun the gear train sings, faintly, well under the recording.
        val whineHz = (f * 12f).coerceIn(400f, 1400f)
        // A bang for the engines whose recording has no backfire: a burst through a pipe
        // the size of this engine's and the body around it, not a naked hiss.
        pipeA.set(profile.pipeHz, 0.72f, 3200f)
        body.set(330f, 1.3f, sampleRate)
        val popsRecorded = b.pops.isNotEmpty()
        val popGain = if (profile.whistle) 0f else 1f

        // Pops: a toot of the whistle, the recording's own backfire, or the pipe's bang.
        repeat(params.pendingPops) {
            when {
                profile.whistle -> whistle.blow(0.45f + 0.25f * abs(nextNoise()))
                popsRecorded -> lay(b.pops[(abs(nextNoise()) * b.pops.size).toInt().coerceAtMost(b.pops.size - 1)], 0.9f)
                else -> popQueue.addLast(Pop(remaining = (sampleRate * 0.14f).toInt()))
            }
        }
        // The boiler blows off while the locomotive stands; it stops as soon as it moves.
        if (profile.whistle) {
            val standing = if (effRpm <= profile.idleRpm * 1.03f) 1f else 0f
            blowOff += (count * dt) / (if (standing > blowOff) 4f else 0.6f) * (standing - blowOff)
            wind.set(2600f, 0.35f, sampleRate)
        }

        // How far the grains are pushed past the rate they were recorded at. Laid down
        // dead regular they would be a perfectly periodic train, a whine; a real engine
        // never fires twice alike, so each cycle lands a little early or late and a
        // little louder or softer, and the further the stretch the more the grains
        // are drawn from all over the band instead of in recorded order.
        val stretch0 = f / bands[lo].hz
        val stretch1 = f / bands[hi].hz
        val jitter = 0.025f * hop

        val twoPi = 2.0 * PI
        var peak = 0f
        for (i in 0 until count) {
            hopLeft -= 1f
            if (hopLeft <= 0f) {
                hopLeft += hop + jitter * nextNoise()
                // The running engine comes in under the last two thirds of the start.
                val fadeIn = if (startupSeconds > 0f) ((grainAge - startupSeconds * 0.3f) / (startupSeconds * 0.7f)).coerceIn(0f, 1f) else 1f
                val g = fadeIn * loadGain * (0.9f + 0.2f * abs(nextNoise()))
                if (w0 > 0.001f) lay(nextGrain(0, bands[lo], stretch0), w0 * dens0 * g)
                if (hi != lo && w1 > 0.001f) lay(nextGrain(1, bands[hi], stretch1), w1 * dens1 * g)
                // Overrun crackle: now and then one of the recording's own pops, quietly.
                if (popsRecorded && brakeAmt > 0.05f && abs(nextNoise()) < 0.05f * brakeAmt) {
                    lay(b.pops[(abs(nextNoise()) * b.pops.size).toInt().coerceAtMost(b.pops.size - 1)], 0.3f)
                }
            }
            var sample = 0f
            for (s in 0 until GRAINS) {
                val buf = gBuf[s] ?: continue
                val p = gPos[s]
                sample += buf[p] * gGain[s]
                if (p + 1 >= buf.size) gBuf[s] = null else gPos[s] = p + 1
            }
            if (popQueue.isNotEmpty()) {
                val bang = renderPops() * popGain
                sample += pipeA.process(bang * 1.6f) * 0.9f + body.process(bang) * 0.5f
            }
            if (brakeAmt > 0.001f) {
                brakePhase += whineHz * dt
                if (brakePhase >= 1.0) brakePhase -= 1.0
                sample += brakeAmt * sin(twoPi * brakePhase).toFloat() * 0.025f
            }
            if (profile.whistle) {
                if (whistle.active) sample += whistle.next(dt) { nextNoise() }
                if (blowOff > 0.002f) sample += wind.process(nextNoise()) * 0.05f * blowOff
            }
            grainAge += dt

            lpf1 += lpAlpha * (sample - lpf1)
            lpf2 += lpAlpha * (lpf1 - lpf2)
            val h1 = hpCoef * (hpf1Out + lpf2 - hpf1In); hpf1In = lpf2; hpf1Out = h1
            val h2 = hpCoef * (hpf2Out + h1 - hpf2In); hpf2In = h1; hpf2Out = h2
            val o = softClip(h2 * masterGain * GRAIN_LEVEL)
            out[outOffset + i] = o
            val a = abs(o)
            if (a > peak) peak = a
        }
        return peak
    }

    /**
     * The next grain of a stream: the one after the last in the recording, with a
     * jump now and then so no loop is heard, and more jumps the further the band is
     * stretched, so the piled-up grains are not copies of each other.
     */
    private fun nextGrain(stream: Int, band: GrainBank.Band, stretch: Float): FloatArray {
        var run = streamRun[stream]
        val jump = 0.03f + 0.3f * (stretch - 1f).coerceIn(0f, 1f)
        if (run == null || streamIdx[stream] >= run.size || abs(nextNoise()) < jump) {
            run = band.runs[(abs(nextNoise()) * band.runs.size).toInt().coerceAtMost(band.runs.size - 1)]
            streamRun[stream] = run
            streamIdx[stream] = if (run.size > 8) (abs(nextNoise()) * (run.size - 4)).toInt() else 0
        }
        return run[streamIdx[stream]++]
    }

    /** Puts a grain (or a start, or a pop) into the air; the quietest slot gives way if all are busy. */
    private fun lay(buf: FloatArray, gain: Float) {
        var slot = -1
        var quietest = 0
        for (s in 0 until GRAINS) {
            if (gBuf[s] == null) { slot = s; break }
            if (gGain[s] * (gBuf[s]!!.size - gPos[s]) < gGain[quietest] * (gBuf[quietest]!!.size - gPos[quietest])) quietest = s
        }
        if (slot < 0) slot = quietest
        gBuf[slot] = buf; gPos[slot] = 0; gGain[slot] = gain
    }

    private fun renderIce(
        profile: EngineProfile, params: EngineParams, effRpm: Float, rpmNorm: Float,
        masterGain: Float, lpAlpha: Float, hpCoef: Float, out: FloatArray, outOffset: Int, count: Int
    ): Float {
        if (scheduleKey != profile.key) {
            schedule = FiringSchedule(profile.firingAngles, profile.cycleDegrees)
            scheduleKey = profile.key
            // Every cylinder its own voice, fixed for this engine, so the cycle is
            // heard as a pattern and not as a metronome.
            var seed = profile.key.hashCode()
            fun unit(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }
            val cyls = profile.firingAngles.size.coerceAtLeast(1)
            cylGain = FloatArray(cyls) { 0.75f + 0.5f * unit() }
            cylLen = FloatArray(cyls) { 0.85f + 0.3f * unit() }
            // Unequal headers: each cylinder's pulse reaches the collector up to 1.6 ms
            // late, so even an even-firing engine's train is never quite regular.
            // That irregularity is the roar's half-orders.
            cylDelay = IntArray(cyls) { (unit() * 0.0016f * sampleRate).toInt() }
            cylBank = IntArray(cyls) { i -> if (i < profile.banks.size) profile.banks[i] and 1 else i and 1 }
            combustion = 0f
            pressure.fill(0f); front.fill(0f); wake.fill(0f); crackEnv = 0f
            pendLeft.fill(0)
            pipeA.reset(); pipeB.reset(); cross1.reset(); cross2.reset(); body.reset()
            bark.reset(); ring.reset(); intake.reset(); tick.reset()
        }
        val sched = schedule!!
        val cyclesPerSec = (effRpm / 60f) * (360f / profile.cycleDegrees)
        val cycleStep = cyclesPerSec * dt
        val load = params.load
        val twoBanks = profile.cylinderCount >= 2

        // The pipe is a length of metal: a delay line whose reflections give every
        // harmonic of the pipe note. Hot gas carries sound a little faster, that is
        // the only thing the revs do to it. Multi-cylinder engines breathe through
        // two banks, the second a shade longer, so the two beat against each other.
        val pipeHz = profile.pipeHz * (1f + 0.08f * rpmNorm)
        // The pipe rings less as the gas flow rises: at full chat it is the pulses you hear, not the pipe.
        val feedback = 0.58f + 0.1f * profile.pipeQ - 0.18f * rpmNorm
        val damping = 2600f + 2400f * rpmNorm
        pipeA.set(pipeHz, feedback, damping)
        pipeB.set(pipeHz * 1.045f, feedback, damping)
        // Cross modes of the pipe and the muffler box, inharmonic to the pipe note.
        val q = (1f + 4f * profile.pipeQ) * (1f - 0.25f * rpmNorm)
        cross1.set(pipeHz * 2.37f, q * 0.8f, sampleRate)
        cross2.set(pipeHz * 3.9f, q * 0.6f, sampleRate)
        // The body: what the frame and the muffler shell add, a wide low-mid bloom.
        body.set(330f, 1.3f, sampleRate)
        // The bark: a wide formant that opens with load, the sound of a throttle body.
        bark.set(650f + 350f * load, 1.1f, sampleRate)
        val ringOn = profile.ringHz > 0f && profile.ringAmount > 0f
        if (ringOn) ring.set(profile.ringHz, 14f, sampleRate)
        // The ring comes up with the revs, so a screamer still idles like an engine.
        val ringAmt = profile.ringAmount * 3f * (0.25f + 0.75f * rpmNorm)
        // Intake and valvetrain: pink noise through a mid band, a valve tick per firing.
        intake.set(800f + 800f * rpmNorm, 0.5f, sampleRate)
        tick.set(3000f, 4f, sampleRate)
        crackleBand.set(1800f, 1.2f, sampleRate)

        val pulseSeconds = profile.pulseMs / 1000f
        // The crack on the front of each pulse: compression, sharper with the revs.
        val crack = profile.compressionTone * (0.5f + 0.3f * rpmNorm)
        val barkAmt = 0.25f + 0.55f * rpmNorm
        val gritAmt = profile.exhaustGrit * (0.05f + 0.95f * load + 0.5f * params.decelAmount)
        val intakeAmt = profile.intakeNoise * (0.12f + 0.88f * load) * (0.3f + 0.7f * rpmNorm)
        // The pipe's upper modes open up with the revs; at idle it is mostly the note.
        val crossAmt = 0.65f * (0.6f + 0.4f * rpmNorm)
        val brakeAmt = params.engineBrakeAmount
        // Overrun: the gear train sings and the exhaust crackles when the throttle is shut at speed.
        val firingRate = cyclesPerSec * profile.cylinderCount
        // Faster firing piles pulses into the pipe; each pulse gives a little so the
        // level climbs with the revs without running away. Relative to this engine's
        // own idle rate, so a slow single and a V16 get the same treatment.
        val idleRate = (profile.idleRpm / 60f) * (360f / profile.cycleDegrees) * profile.cylinderCount
        val rateComp = 1f / kotlin.math.sqrt(1f + (firingRate - idleRate).coerceAtLeast(0f) / (2f * idleRate))
        // The valve tick: one per firing, so on a fast engine it is a buzz; let it thin out as they merge.
        val tickAmt = 0.35f * (0.3f + 0.7f * rpmNorm) * (0.5f + profile.compressionTone) * rateComp
        // How a pulse bleeds off: the profile's length, however fast the engine fires.
        // A pulse squeezed to fit the gap turns a fast engine into a sine wave; letting
        // them overlap keeps the front of every bang, which is what an engine is. Only
        // a chuff longer than the gap (steam) is shortened, so chuffs stay chuffs.
        val pulseEff = minOf(pulseSeconds, maxOf(0.0033f, 0.42f / firingRate.coerceAtLeast(1f)))
        val bleedCoef = exp(-dt / (pulseEff * 1.2f))
        val frontCoef = exp(-dt / 0.0002f)                       // the front rises in a fifth of a millisecond
        val wakeCoef = exp(-dt / (pulseEff * 2.6f))              // the undershoot behind the pressure
        // The noise on the front: a millisecond for a bang, a long hiss for a steam chuff.
        val crackCoef = exp(-dt / maxOf(0.0012f, pulseEff * 0.12f))
        val brakeHz = (firingRate * (if (profile.twoStroke) 9f else 7f)).coerceIn(600f, 3400f)
        val cracklePerFiring = 0.35f * brakeAmt
        val tickLen = 0.0015f

        val twoPi = 2.0 * PI
        var peak = 0f
        for (i in 0 until count) {
            // Crank turns; every firing that the step crosses starts a pulse.
            val fired = sched.advance(cycleStep)
            if (fired > 0) {
                tickAge = 0f
                val cyl = sched.lastFired.coerceIn(0, cylGain.size - 1)
                // The burn drifts a little from cycle to cycle, and each cylinder
                // has its own voice: no two firings alike.
                combustion = (combustion * 0.9f + 0.07f * nextNoise()).coerceIn(-0.3f, 0.3f)
                val amp = rateComp * (0.6f + 0.4f * load) * cylGain[cyl] * cylLen[cyl] *
                    (1f + combustion) * (0.95f + 0.1f * abs(nextNoise()))
                // Down the header it goes; the oldest slot gives way if all are busy.
                var slot = 0
                for (s in 0 until PENDING) if (pendLeft[s] <= 0) { slot = s; break } else if (pendLeft[s] < pendLeft[slot]) slot = s
                pendAmp[slot] = amp
                pendBank[slot] = if (twoBanks) cylBank[cyl] else 0
                pendLeft[slot] = cylDelay[cyl] + 1
                if (brakeAmt > 0.01f && abs(nextNoise()) < cracklePerFiring) {
                    popQueue.addLast(Pop(remaining = (sampleRate * 0.05f).toInt(), gain = 0.35f))
                }
            }
            // Pulses arriving at the collector: pressure with its front, and the undershoot behind.
            for (s in 0 until PENDING) {
                if (pendLeft[s] > 0 && --pendLeft[s] == 0) {
                    val b = pendBank[s]; val amp = pendAmp[s]
                    pressure[b] += amp
                    front[b] += amp
                    wake[b] += amp * 0.55f
                    crackEnv += amp
                }
            }
            // Each bank's pulse: pressure minus its front (so it rises in a third of a
            // millisecond however fast the engine fires), minus the undershoot that follows.
            var exciteSum = 0f
            for (b in 0..1) {
                pressure[b] *= bleedCoef; front[b] *= frontCoef; wake[b] *= wakeCoef
            }
            val exciteA = pressure[0] - front[0] - wake[0] * 0.55f
            val exciteB = pressure[1] - front[1] - wake[1] * 0.55f
            // A crack of noise on the front of every bang, sharper with the revs.
            crackEnv *= crackCoef
            val crackN = crackEnv * crack * (1f + 0.6f * load) * nextNoise()
            exciteSum = (exciteA + exciteB) * (1.1f - 0.5f * crack) + crackN

            // Pops: a bang through the same pipe, decaying.
            var popIn = 0f
            if (popQueue.isNotEmpty()) popIn = renderPops()

            val shared = popIn * 2f + gritAmt * 0.2f * nextNoise()
            val pipeIn = exciteSum + shared
            val exhaust = if (twoBanks) {
                pipeA.process(exciteA * (1.1f - 0.5f * crack) + crackN * 0.5f + shared * 0.5f) +
                    pipeB.process(exciteB * (1.1f - 0.5f * crack) + crackN * 0.5f + shared * 0.5f)
            } else {
                pipeA.process(pipeIn)
            }
            var sample = exhaust * 0.6f +
                cross1.process(pipeIn) * crossAmt +
                cross2.process(pipeIn) * crossAmt * 0.7f +
                body.process(exhaust) * 0.35f +
                bark.process(pipeIn * load) * barkAmt
            if (ringOn) sample += ring.process(exciteSum * crack) * ringAmt

            // Air: intake roar, a valve tick per firing, and exhaust grit.
            val pinkN = pink.next()
            sample += intake.process(pinkN) * intakeAmt * 2f
            if (tickAge < tickLen) {
                sample += tick.process(nextNoise()) * tickAmt * (1f - tickAge / tickLen)
            }
            tickAge += dt
            // Grit: a little pink rumble and a rasp of white on top.
            sample += gritAmt * (0.1f * pinkN + 0.08f * nextNoise())

            // Overrun whine and crackle.
            if (brakeAmt > 0.001f) {
                brakePhase += brakeHz * dt
                if (brakePhase >= 1.0) brakePhase -= 1.0
                sample += brakeAmt * (sin(twoPi * brakePhase).toFloat() * 0.07f + crackleBand.process(nextNoise()) * 0.1f)
            }

            // Muffler, sub cut, soft clip.
            lpf1 += lpAlpha * (sample - lpf1)
            lpf2 += lpAlpha * (lpf1 - lpf2)
            val h1 = hpCoef * (hpf1Out + lpf2 - hpf1In); hpf1In = lpf2; hpf1Out = h1
            val h2 = hpCoef * (hpf2Out + h1 - hpf2In); hpf2In = h1; hpf2Out = h2
            val o = softClip(h2 * masterGain)
            out[outOffset + i] = o
            val a = abs(o)
            if (a > peak) peak = a
        }
        return peak
    }

    private fun renderSynth(
        profile: EngineProfile, params: EngineParams, rpmNorm: Float,
        masterGain: Float, lpAlpha: Float, hpCoef: Float, out: FloatArray, outOffset: Int, count: Int
    ): Float {
        val load = params.load
        val voice = profile.voice
        // A turbine spools; the rest follow the revs at once.
        val targetNorm = rpmNorm
        if (spooledNorm < 0f) spooledNorm = targetNorm
        val spoolAlpha = if (voice == SynthVoice.TURBINE) (count * dt) / (if (targetNorm > spooledNorm) 0.5f else 0.9f) else 1f
        spooledNorm += (targetNorm - spooledNorm) * spoolAlpha.coerceAtMost(1f)
        val n = spooledNorm
        val freq = profile.synthBaseHz + (profile.synthMaxHz - profile.synthBaseHz) * n
        val wobble = profile.synthFmDepth
        val brakeAmt = params.engineBrakeAmount
        val grit = profile.exhaustGrit

        when (voice) {
            SynthVoice.TURBINE -> {
                rumble.set(260f + 120f * n, 0.9f, sampleRate)
                roar.set(1300f + 900f * n, 0.6f, sampleRate)
            }
            SynthVoice.ELECTRIC -> wind.set(2500f + 1500f * n, 0.5f, sampleRate)
            SynthVoice.BUBBLE -> wind.set(900f + 600f * n, 0.6f, sampleRate)
            else -> wind.set(1800f, 0.8f, sampleRate)
        }
        // The Jetsons car: blips come 5 a second at rest, 14 a second flat out.
        val blipRate = 5f + 9f * n

        val twoPi = 2.0 * PI
        var peak = 0f
        for (i in 0 until count) {
            // Two slow LFOs: vibrato and the tremolo wobble.
            lfoPhase += (4.5f + 2.5f * n) * dt
            if (lfoPhase >= 1.0) lfoPhase -= 1.0
            lfo2Phase += (0.7f + 1.1f * n) * dt
            if (lfo2Phase >= 1.0) lfo2Phase -= 1.0
            val vib = sin(twoPi * lfoPhase).toFloat()
            val trem = 0.5f + 0.5f * sin(twoPi * lfo2Phase).toFloat()
            val f = freq * (1f + 0.012f * wobble * vib)
            val inc = (f * dt).toDouble()

            var sample = 0f
            when (voice) {
                SynthVoice.HUM -> {
                    // Three partials, the upper two swelling with the wobble.
                    oscPhase = wrap(oscPhase + inc)
                    val p = twoPi * oscPhase
                    sample = sin(p).toFloat() * 0.55f +
                        sin(2.0 * p).toFloat() * (0.2f + 0.25f * wobble * trem) +
                        sin(3.0 * p).toFloat() * (0.1f + 0.15f * wobble * (1f - trem))
                    sample *= 0.8f + 0.2f * (1f - wobble) + 0.2f * wobble * trem
                    sample += wind.process(pink.next()) * grit * 1.5f
                }
                SynthVoice.SAW -> {
                    // Three saws, two of them detuned either side, and a sub octave
                    // once it is high enough for the speaker to carry it.
                    oscPhase = wrap(oscPhase + inc)
                    osc2Phase = wrap(osc2Phase + inc * (1.0 + 0.006 * (0.5f + wobble)))
                    subPhase = wrap(subPhase + inc * (1.0 - 0.004 * (0.5f + wobble)))
                    val subAmt = 0.22f * ((f - 220f) / 300f).coerceIn(0f, 1f)
                    sample = (saw(oscPhase.toFloat(), inc.toFloat()) +
                        saw(osc2Phase.toFloat(), inc.toFloat()) * 0.7f +
                        saw(subPhase.toFloat(), inc.toFloat()) * 0.7f) * 0.25f +
                        whine(4, inc * 0.5) * subAmt
                    sample *= 1f - 0.25f * wobble * trem
                    sample += wind.process(pink.next()) * grit * 1.2f * (0.3f + 0.7f * load)
                    sample += grit * 0.06f * nextNoise() * load
                }
                SynthVoice.SQUARE -> {
                    oscPhase = wrap(oscPhase + inc)
                    subPhase = wrap(subPhase + inc * 0.5)
                    // The sub octave only once it is high enough for the speaker to play it.
                    val subAmt = 0.2f * ((f - 220f) / 300f).coerceIn(0f, 1f)
                    sample = square(oscPhase.toFloat(), inc.toFloat()) * 0.32f + sin(twoPi * subPhase).toFloat() * subAmt
                    sample *= 1f - 0.2f * wobble * trem
                    sample += wind.process(pink.next()) * grit
                }
                SynthVoice.TURBINE -> {
                    val pn = pink.next()
                    val air = rumble.process(pn) * (0.5f + 0.5f * n) * 1.6f + roar.process(pn) * (0.15f + 0.85f * n) * 1.3f
                    sample = air * (0.5f + grit)
                    // The shaft whine and its blade-pass partials, louder as it spools up.
                    val w = (0.06f + 0.3f * n) * profile.synthWhine
                    sample += whine(0, inc) * w +
                        whine(1, inc * 1.47) * w * 0.4f +
                        whine(2, inc * 2.0) * w * 0.35f +
                        whine(3, inc * 3.0) * w * 0.2f
                    // Two engines: a second shaft a shade off the first, so they beat and the air throbs.
                    if (wobble > 0.2f) {
                        sample += whine(4, inc * (1.0 + 0.045 * wobble)) * w * 0.8f
                        sample *= 1f - 0.35f * wobble * trem
                    }
                    sample += nextNoise() * 0.02f * n
                }
                SynthVoice.BUBBLE -> {
                    // A run of blips. Each one is a short "bloop": the level swells in four
                    // milliseconds and rings off, the pitch slides down a fourth as it goes,
                    // and every blip sits a little off the last so the run bubbles rather than beeps.
                    blipPhase += blipRate * dt
                    if (blipPhase >= 1f) {
                        blipPhase -= 1f
                        blipT = 0f
                        blipLen = 0.6f / blipRate
                        blipHz = freq * (1f + 0.3f * wobble * nextNoise())
                    }
                    val u = blipT / blipLen
                    if (u < 1f) {
                        val glide = blipHz * (1f - 0.28f * u)
                        oscPhase = wrap(oscPhase + (glide * dt).toDouble())
                        val env = (blipT / 0.004f).coerceAtMost(1f) * exp(-3.2f * u)
                        val p = twoPi * oscPhase
                        sample = (sin(p).toFloat() * 0.75f + sin(2.0 * p).toFloat() * 0.2f) * env
                    }
                    blipT += dt
                    // The hull: a soft hum an octave under the blips, and a breath of air.
                    subPhase = wrap(subPhase + inc * 0.5)
                    sample += sin(twoPi * subPhase).toFloat() * 0.1f * (0.7f + 0.3f * trem)
                    sample += wind.process(pink.next()) * grit * (1f + 2f * load)
                    sample *= 0.8f
                }
                SynthVoice.ELECTRIC -> {
                    // Motor whine, its inverter partials and a touch of saw for bite.
                    oscPhase = wrap(oscPhase + inc)
                    val w = 0.4f + 0.25f * n
                    sample = whine(0, inc) * w +
                        whine(1, inc * 2.0) * w * 0.35f +
                        whine(2, inc * 1.03) * w * 0.12f +
                        whine(3, inc * 4.0) * w * 0.08f +
                        saw(oscPhase.toFloat(), inc.toFloat()) * 0.12f
                    sample += wind.process(pink.next()) * (0.05f + 0.25f * n) * (0.4f + 0.6f * load)
                }
            }

            // Regen on the overrun: a thin whine a fifth up and some air.
            if (brakeAmt > 0.001f) {
                brakePhase = wrap(brakePhase + inc * 1.5)
                sample += brakeAmt * (sin(twoPi * brakePhase).toFloat() * 0.07f + nextNoise() * 0.02f)
            }
            if (popQueue.isNotEmpty()) sample += crackleBand.process(renderPops()) * 2f

            lpf1 += lpAlpha * (sample - lpf1)
            lpf2 += lpAlpha * (lpf1 - lpf2)
            val h1 = hpCoef * (hpf1Out + lpf2 - hpf1In); hpf1In = lpf2; hpf1Out = h1
            val h2 = hpCoef * (hpf2Out + h1 - hpf2In); hpf2In = h1; hpf2Out = h2
            val o = softClip(h2 * masterGain)
            out[outOffset + i] = o
            val a = abs(o)
            if (a > peak) peak = a
        }
        return peak
    }

    private fun whine(slot: Int, inc: Double): Float {
        whinePhases[slot] = wrap(whinePhases[slot] + inc)
        return sin(2.0 * PI * whinePhases[slot]).toFloat()
    }

    private fun wrap(p: Double): Double = if (p >= 1.0) p - 1.0 else p

    /** polyBLEP: the step's alias energy taken back out around the discontinuity. */
    private fun blep(t: Float, dtp: Float): Float = when {
        t < dtp -> { val x = t / dtp; x + x - x * x - 1f }
        t > 1f - dtp -> { val x = (t - 1f) / dtp; x * x + x + x + 1f }
        else -> 0f
    }

    private fun saw(t: Float, dtp: Float): Float = (2f * t - 1f) - blep(t, dtp)

    private fun square(t: Float, dtp: Float): Float {
        var v = if (t < 0.5f) 1f else -1f
        v += blep(t, dtp)
        var t2 = t + 0.5f
        if (t2 >= 1f) t2 -= 1f
        v -= blep(t2, dtp)
        return v
    }

    private fun onePoleAlpha(hz: Float): Float {
        val rc = 1f / (2f * PI.toFloat() * hz)
        return dt / (rc + dt)
    }

    /** Gentle knee: nothing past 0.97, the loud parts rounded rather than cut. */
    private fun softClip(x: Float): Float {
        if (x >= 3f) return 0.97f
        if (x <= -3f) return -0.97f
        val x2 = x * x
        return 0.97f * x * (27f + x2) / (27f + 9f * x2)
    }

    /** Renders the next sample of any active pops as a noise burst; the caller shapes it. */
    private fun renderPops(): Float {
        var sum = 0f
        val it = popQueue.iterator()
        while (it.hasNext()) {
            val pop = it.next()
            if (pop.remaining <= 0) { it.remove(); continue }
            val t = 1f - pop.remaining.toFloat() / pop.total
            sum += exp(-7f * t) * pop.gain * nextNoise()
            pop.remaining--
        }
        return sum
    }

    /** Reset all state, call when changing profile or after long silence. */
    fun reset() {
        schedule = null
        scheduleKey = null
        bankKey = null; bank = null
        gBuf.fill(null); hopLeft = 0f; grainAge = 0f
        streamBand.fill(-1); streamRun.fill(null)
        whistle.stop(); blowOff = 0f
        blipPhase = 1f; blipT = 1e9f
        tickAge = 1e9f
        pressure.fill(0f); front.fill(0f); wake.fill(0f); crackEnv = 0f
        pendLeft.fill(0)
        combustion = 0f
        oscPhase = 0.0; osc2Phase = 0.0; subPhase = 0.0; lfoPhase = 0.0; lfo2Phase = 0.0
        brakePhase = 0.0; gearPhase = 0.0
        spooledNorm = -1f
        whinePhases.fill(0.0)
        lpf1 = 0f; lpf2 = 0f
        hpf1In = 0f; hpf1Out = 0f; hpf2In = 0f; hpf2Out = 0f
        pipeA.reset(); pipeB.reset(); cross1.reset(); cross2.reset(); body.reset()
        bark.reset(); ring.reset(); intake.reset(); tick.reset(); crackleBand.reset()
        rumble.reset(); roar.reset(); wind.reset()
        popQueue.clear()
    }

    private class Pop(var remaining: Int, val gain: Float = 1f) {
        val total: Int = remaining.coerceAtLeast(1)
    }

    /**
     * A steam whistle: three chimes a third and a fifth apart with their second
     * harmonics, a breath of steam shaped by the note, the pitch coming up as the
     * valve opens and sagging as it shuts.
     */
    private class Whistle {
        private var left = 0f
        private var total = 0f
        private var delay = 0f
        private val phase = DoubleArray(3)
        private var vib = 0.0
        private var breath = 0f
        val active: Boolean get() = left > 0f || delay > 0f

        fun blow(seconds: Float, after: Float = 0f) {
            if (left > 0f && delay <= 0f) { left = maxOf(left, seconds); total = maxOf(total, left); return }
            total = seconds; left = seconds; delay = after
        }

        fun stop() { left = 0f; delay = 0f }

        fun next(dt: Float, noise: () -> Float): Float {
            if (delay > 0f) { delay -= dt; return 0f }
            if (left <= 0f) return 0f
            val t = total - left
            left -= dt
            val rise = (t / 0.14f).coerceIn(0f, 1f)
            val fall = (left / 0.22f).coerceIn(0f, 1f)
            val env = rise * fall
            val pitch = 1f - 0.2f * (1f - rise) - 0.1f * (1f - fall)
            vib += 5.5 * dt
            if (vib >= 1.0) vib -= 1.0
            val f0 = 466f * pitch * (1f + 0.006f * sin(2.0 * PI * vib).toFloat())
            var s = 0f
            for (i in 0..2) {
                phase[i] += f0 * RATIOS[i] * dt
                if (phase[i] >= 1.0) phase[i] -= 1.0
                val p = 2.0 * PI * phase[i]
                s += (sin(p).toFloat() + 0.3f * sin(2.0 * p).toFloat()) * AMPS[i]
            }
            breath += 0.35f * (noise() - breath)
            s += breath * 0.3f
            return s * env * 0.42f
        }

        private companion object {
            val RATIOS = floatArrayOf(1f, 1.26f, 1.5f)
            val AMPS = floatArrayOf(0.5f, 0.32f, 0.28f)
        }
    }

    private companion object {
        const val OUT_LEVEL = 0.9f
        const val PENDING = 16
        /** Grains in the air at once: at six times the recorded rate, twelve overlap. */
        const val GRAINS = 24
        /** Banks are cut to -16 dB rms at idle, where the synth engines sit; the revs are louder as recorded. */
        const val GRAIN_LEVEL = 1.0f
    }
}

/**
 * Where the cylinders fire in the cycle. [advance] moves the crank by a
 * fraction of a cycle and returns how many firings that step crossed.
 */
class FiringSchedule(angles: FloatArray, cycleDegrees: Float) {
    private val points: DoubleArray = angles.map { (it / cycleDegrees).toDouble().mod(1.0) }.sorted().toDoubleArray()
    /** Position in the cycle, 0..1. */
    private var phase: Double = 0.0
    /** The next point to fire; [points].size means the cycle's end comes first. */
    private var next: Int = 0
    /** Which cylinder (in firing order) fired last, 0-based. */
    var lastFired: Int = 0
        private set

    fun advance(step: Float): Int {
        if (points.isEmpty() || step <= 0f) return 0
        var fired = 0
        var target = phase + step
        while (true) {
            if (next < points.size) {
                if (points[next] < target - EPS) { fired++; lastFired = next; next++ } else break
            } else {
                if (1.0 < target - EPS) { target -= 1.0; next = 0 } else break
            }
        }
        phase = target
        return fired
    }

    private companion object { const val EPS = 1e-5 }
}

/**
 * A length of exhaust pipe: a delay line fed back on itself with a damped loop,
 * so a pulse dropped in rings at the pipe note and every harmonic of it, the
 * high ones dying first. Fractional length so the note is exact.
 */
private class Pipe(private val sampleRate: Int) {
    private val buf = FloatArray(SIZE)
    private var idx = 0
    private var delay = 100f
    private var feedback = 0.5f
    private var dampAlpha = 0.3f
    private var lp = 0f

    fun set(hz: Float, fb: Float, dampHz: Float) {
        delay = (sampleRate / hz.coerceAtLeast(12f)).coerceIn(2f, (SIZE - 2).toFloat())
        feedback = fb.coerceIn(0f, 0.95f)
        val rc = 1f / (2f * PI.toFloat() * dampHz)
        val dt = 1f / sampleRate
        dampAlpha = dt / (rc + dt)
    }

    fun process(x: Float): Float {
        val di = delay.toInt()
        val frac = delay - di
        var r0 = idx - di; if (r0 < 0) r0 += SIZE
        var r1 = r0 - 1; if (r1 < 0) r1 += SIZE
        val back = buf[r0] + (buf[r1] - buf[r0]) * frac
        lp += dampAlpha * (back - lp)
        val out = x + feedback * lp
        buf[idx] = out
        idx++; if (idx == SIZE) idx = 0
        return out
    }

    fun reset() { buf.fill(0f); lp = 0f }

    private companion object { const val SIZE = 4096 }
}

/** Chamberlin state-variable band-pass, the ring of a pipe or a formant. */
private class Resonator {
    private var low = 0f
    private var band = 0f
    private var f = 0.1f
    private var q = 1f

    fun set(hz: Float, quality: Float, sampleRate: Int) {
        f = (2f * sin((PI * hz / sampleRate).toFloat())).coerceAtMost(0.9f)
        q = 1f / quality.coerceAtLeast(0.5f)
    }

    fun process(x: Float): Float {
        low += f * band
        val high = x - low - q * band
        band += f * high
        return band
    }

    fun reset() { low = 0f; band = 0f }
}

/** Paul Kellet's economy pink noise, -1..1. */
private class PinkNoise {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var rng = 0x2F6E2B1
    fun next(): Float {
        var x = rng
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        rng = x
        val white = x.toFloat() / Int.MAX_VALUE.toFloat()
        b0 = 0.99765f * b0 + white * 0.0990460f
        b1 = 0.96300f * b1 + white * 0.2965164f
        b2 = 0.57000f * b2 + white * 1.0526913f
        return (b0 + b1 + b2 + white * 0.1848f) * 0.25f
    }
}
