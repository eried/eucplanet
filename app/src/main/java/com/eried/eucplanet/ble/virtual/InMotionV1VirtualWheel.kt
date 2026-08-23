package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.InMotionV1Protocol
import com.eried.eucplanet.ble.InMotionV1Protocol.CanId

/**
 * Software fake of an InMotion V8S, emitting real V1 wire bytes: the
 * `AA AA … 55 55` envelope with `0xA5` stuffing around a 16-byte CAN prefix and
 * an extended payload, so [com.eried.eucplanet.ble.InMotionV1Parser] does the
 * decoding exactly as it would for the wheel.
 *
 * Built for the long run rather than for a screenshot. The script covers the
 * two stretches a V8S is hard to reason about from a desk:
 *
 * - **A ride with real regen.** Cycles of accelerate, cruise, brake and stop, so
 *   the pack current goes negative several times a minute. That is what showed
 *   the ride-efficiency window being wiped on every brake, leaving CONSUMPTION
 *   and RANGE blank for most of a ride.
 * - **A charge with no charge current.** The wheel keeps reporting its own idle
 *   draw (about +0.02 A) while the pack voltage climbs, which is what a V8S
 *   really does. That is the case where energy integration has nothing to work
 *   with and the Battery screen has to fall back to the pack size.
 *
 * The timeline is 40 minutes of riding, 2 parked, then 120 on the charger, and
 * it loops, so leaving it connected overnight keeps exercising both. Watching the
 * charge means waiting out the ride: it is deliberately long, because that is
 * where the rolling window and the charge detector are worth watching.
 *
 * The pack is modelled rather than scripted: a state of charge in watt-hours
 * that the ride spends and the charger refills, mapped back to a terminal
 * voltage through an open-circuit curve and an internal resistance. Battery
 * percent is not on the wire at all on this family, so the app derives it from
 * that voltage, sag under load and rebound at a standstill included. That
 * rebound is worth having: it is what a percent-climb charge detector has to
 * tell apart from a real charger.
 */
class InMotionV1VirtualWheel(
    /** Wall clock, injectable so a test can replay a two-hour script in a
     *  millisecond instead of waiting for one. */
    private val clockMs: () -> Long = System::currentTimeMillis,
) : VirtualWheel {

    override val displayName = "Virtual InMotion V8S"
    override val id = "V8S"

    /** Routes the dispatcher to the V1 adapter and names the model (V8S). */
    override val bleName = "V8S-VIRTUAL"

    private companion object {
        // --- Script phases, in order, then it starts over. ---
        const val RIDE_MS = 40 * 60_000L
        const val PARK_MS = 2 * 60_000L
        const val CHARGE_MS = 120 * 60_000L
        const val SCRIPT_MS = RIDE_MS + PARK_MS + CHARGE_MS

        /** One accelerate / cruise / brake / stop cycle, in seconds. */
        const val CYCLE_S = 72

        /** V8S pack, and where the script starts it. */
        const val PACK_WH = 1000f
        const val START_SOC = 0.92f

        /** Open-circuit volts across the whole pack, near enough the inverse of
         *  the V8 family curve in InMotionV1Parser (68 V empty, 82.5 V full).
         *  The span runs a couple of tenths past the curve's top so a full pack
         *  reads 100 % rather than 99 % once its idle draw is subtracted. */
        const val VOLT_EMPTY = 68f
        const val VOLT_SPAN = 14.7f

        /** Pack internal resistance. 14 A of acceleration pulls the terminal
         *  voltage down about a volt, which is a V8S to within a tenth. */
        const val PACK_OHMS = 0.08f

        /** Charger output at the pack. About 0.7 % a minute on this pack. */
        const val CHARGER_W = 400f

        /** What the board itself draws with the motor idle. The V8S reports
         *  this and nothing else while it charges. */
        const val IDLE_AMPS = 0.02f

        const val SERIAL_HEX = "V8SVIRT0"
        const val MODEL_ID_V8S = 87
    }

    private var startMs = clockMs()
    // Kept in doubles and reported as whole metres, the way the wheel does, so
    // rounding each frame down cannot quietly cost the ride eight percent of its
    // distance and inflate every Wh per km that follows.
    private var odoMeters = 2_517_000.0
    private var tripMeters = 0.0
    private var socWh = PACK_WH * START_SOC
    private var lastStepMs = 0L
    private var lightOn = false
    private var locked = false
    private var maxSpeedKmh = 45f
    private var volumePercent = 50

    override fun reset() {
        startMs = clockMs()
        odoMeters = 2_517_000.0
        tripMeters = 0.0
        socWh = PACK_WH * START_SOC
        lastStepMs = 0L
        lightOn = false
        locked = false
        maxSpeedKmh = 45f
        volumePercent = 50
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        val frame = InMotionV1Protocol.unwrap(data) ?: return emptyList()
        if (frame.size < 16) return emptyList()
        val canId = readU32(frame, 0)
        val payload = frame.copyOfRange(4, 12)

        return when (canId) {
            CanId.FAST_INFO -> listOf(fastInfoFrame())
            CanId.SLOW_INFO -> listOf(slowInfoFrame())
            CanId.PIN -> listOf(ackFrame(CanId.PIN))
            CanId.HEADLIGHT -> {
                lightOn = payload[0].toInt() == 0x01
                listOf(ackFrame(CanId.HEADLIGHT))
            }
            CanId.RIDE_MODE -> {
                if (payload[0].toInt() == 0x01) {
                    maxSpeedKmh = ((payload[4].toInt() and 0xFF) or
                        ((payload[5].toInt() and 0xFF) shl 8)) / 1000f
                }
                listOf(ackFrame(CanId.RIDE_MODE))
            }
            CanId.VOLUME -> {
                volumePercent = (((payload[0].toInt() and 0xFF) or
                    ((payload[1].toInt() and 0xFF) shl 8)) / 100).coerceIn(0, 100)
                listOf(ackFrame(CanId.VOLUME))
            }
            CanId.REMOTE_CTRL -> {
                when (payload[4].toInt() and 0xFF) {
                    0x03 -> locked = true
                    0x04 -> locked = false
                }
                listOf(ackFrame(CanId.REMOTE_CTRL))
            }
            else -> listOf(ackFrame(canId))
        }
    }

    // --- The script ---

    /** Where the script is. Running off the end starts a fresh session, pack
     *  included, so leaving it connected replays rather than drifts. */
    private fun elapsed(): Long {
        val t = clockMs() - startMs
        if (t < 0L || t >= SCRIPT_MS) {
            reset()
            return 0L
        }
        return t
    }

    /** Speed in km/h at [t], from the accelerate / cruise / brake / stop cycle. */
    private fun speedKmh(t: Long): Float {
        if (t >= RIDE_MS) return 0f
        val phase = ((t / 1000L) % CYCLE_S).toInt()
        return when {
            phase < 8 -> phase / 8f * 25f
            phase < 56 -> 25f
            phase < 64 -> 25f - (phase - 56) / 8f * 25f
            else -> 0f
        }
    }

    /**
     * Pack current in the wheel's own sign convention: POSITIVE while the wheel
     * discharges, negative on regen. Braking is what makes the ride's energy
     * counters non-monotonic; the charge stretch deliberately never goes
     * negative, because a real V8S never reports the charger.
     */
    private fun amps(t: Long): Float {
        if (t >= RIDE_MS) return IDLE_AMPS
        val phase = ((t / 1000L) % CYCLE_S).toInt()
        return when {
            phase < 8 -> 14f     // accelerating
            phase < 56 -> 4.2f   // cruising, about 15 Wh/km at 25 km/h
            phase < 64 -> -5f    // braking, energy back into the pack
            else -> IDLE_AMPS    // stopped at the light
        }
    }

    /** True once the script has the wheel on the charger. */
    private fun charging(t: Long): Boolean = t >= RIDE_MS + PARK_MS && socWh < PACK_WH

    /**
     * Terminal volts: the open-circuit voltage for the current state of charge,
     * less what the pack's own resistance drops under load. Positive current
     * (discharge) sags it, braking lifts it, and standing still lets it back up.
     */
    private fun volts(t: Long): Float {
        val ocv = VOLT_EMPTY + VOLT_SPAN * (socWh / PACK_WH)
        return (ocv - amps(t) * PACK_OHMS).coerceIn(VOLT_EMPTY, VOLT_EMPTY + VOLT_SPAN)
    }

    /**
     * Move the odometer and the pack on by however long it has been since the
     * last frame. Energy comes out of the pack at V x I while riding and goes
     * back in at the charger's rate, so the percentage the app derives from
     * voltage tracks what the ride actually spent.
     */
    private fun advance(t: Long) {
        val stepMs = if (lastStepMs == 0L) 0L else (t - lastStepMs).coerceIn(0L, 5_000L)
        lastStepMs = t
        if (stepMs == 0L) return
        val metres = speedKmh(t) / 3.6 * (stepMs / 1000.0)
        odoMeters += metres
        tripMeters += metres

        val hours = stepMs / 3_600_000f
        socWh = if (charging(t)) {
            socWh + CHARGER_W * hours
        } else {
            socWh - volts(t) * amps(t) * hours
        }.coerceIn(0f, PACK_WH)
    }

    // --- Frame builders ---

    /**
     * Fast info (`0x0F550113`), 76-byte extended payload laid out per
     * docs/protocols/inmotion_v1.md section 4.
     */
    private fun fastInfoFrame(): ByteArray {
        val t = elapsed()
        advance(t)
        val payload = ByteArray(76)
        val speed = speedKmh(t)
        // speed_kmh = |(A + B) / (2 * 3812)| * 3.6, so both samples carry the same value.
        val sample = (speed / 3.6f * 3812f).toInt()
        putI32(payload, 0, (1.5f * 65536f).toInt())      // pitch, degrees * 65536
        putI32(payload, 12, sample)
        putI32(payload, 16, sample)
        putI32(payload, 20, (amps(t) * 100f).toInt())    // 0.01 A units
        putU32(payload, 24, (volts(t) * 100f).toInt().toLong())
        payload[32] = 35                                  // motor temp, C
        payload[34] = 30                                  // board temp, C
        putU32(payload, 44, odoMeters.toLong())
        putU32(payload, 48, tripMeters.toLong())
        payload[60] = 0x23                                // work mode, as captured
        payload[61] = if (locked) 0x02 else 0x00
        putI32(payload, 72, 0)                            // roll
        return extendedFrame(CanId.FAST_INFO, payload)
    }

    /**
     * Slow info (`0x0F550114`), 140-byte extended payload per section 5. Carries
     * the model code the adapter narrows capabilities from, plus the settings
     * the Wheel parameters screen reads back.
     */
    private fun slowInfoFrame(): ByteArray {
        val payload = ByteArray(140)
        // Serial: the parser reads bytes 7..0 as hex, so any 8 bytes will do.
        SERIAL_HEX.forEachIndexed { i, c -> payload[i] = c.code.toByte() }
        putU16(payload, 24, 22)                           // firmware patch
        payload[26] = 2                                   // minor
        payload[27] = 1                                   // major -> 1.2.22
        putU16(payload, 60, (maxSpeedKmh * 1000f).toInt())
        payload[80] = if (lightOn) 1 else 0
        payload[104] = MODEL_ID_V8S.toByte()              // car type low byte
        payload[107] = 0                                  // high byte, 2-digit id
        payload[124] = (28 + 4).toByte()                  // pedal sensitivity 4
        putU16(payload, 125, volumePercent * 100)
        payload[129] = 0                                  // handle enabled
        payload[130] = 0                                  // DRL off
        payload[132] = 0                                  // comfort, not classic
        return extendedFrame(CanId.SLOW_INFO, payload)
    }

    /** Command ack: same CAN ID back with `data[0] = 0x01` for success. */
    private fun ackFrame(canId: Int): ByteArray =
        InMotionV1Protocol.buildFrame(canId, byteArrayOf(0x01, 0, 0, 0, 0, 0, 0, 0))

    /**
     * Extended reply: the 16-byte CAN prefix with `len = 0xFE`, the payload
     * length in the data slot, then the payload itself.
     */
    private fun extendedFrame(canId: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(16 + payload.size)
        putU32(frame, 0, canId.toLong() and 0xFFFFFFFFL)
        putU32(frame, 4, payload.size.toLong())
        frame[12] = 0xFE.toByte()   // extended
        frame[13] = 0x05            // channel
        frame[14] = 0x00            // standard format
        frame[15] = 0x00            // data frame
        payload.copyInto(frame, 16)
        return InMotionV1Protocol.wrap(frame)
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putI32(b: ByteArray, off: Int, v: Int) = putU32(b, off, v.toLong() and 0xFFFFFFFFL)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
