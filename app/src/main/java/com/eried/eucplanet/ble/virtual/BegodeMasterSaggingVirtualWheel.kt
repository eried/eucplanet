package com.eried.eucplanet.ble.virtual

import kotlin.random.Random

/**
 * A Begode Master on a pack that behaves like a pack.
 *
 * The stock [BegodeMasterVirtualWheel] holds 123 V for ever, which is right
 * for checking a parser and useless for checking anything that has to cope
 * with a battery. This one does what an 84 V class pack does on a real ride:
 *
 *  - **It discharges.** One point a minute from 36 %, which is a hard ride on
 *    a mid-size pack and puts the resting level through a 30 % alarm about six
 *    minutes in, so the test does not take all afternoon.
 *  - **It sags under load.** Twenty seconds on the throttle, ten coasting,
 *    over and over. On the throttle the reading drops ten points below the
 *    resting level and comes straight back when the rider eases off. That is
 *    the shape a rider's live envelope graph showed swinging, and the reason
 *    this simulator exists.
 *  - **It jitters.** Plus or minus a point and a half of noise on every frame,
 *    seeded so two runs look the same.
 *
 * So a plain Battery alarm at 30 % fires on the first burst of throttle, and a
 * Battery (est) alarm at 30 % fires when the resting level actually gets there,
 * and not before. Both can be watched in logcat under "Alarm fired".
 *
 * Same wire format, same name, same firmware banner as the stock Master, so
 * the whole parser and model path is the real one.
 */
class BegodeMasterSaggingVirtualWheel : VirtualWheel {

    override val displayName = "Virtual Begode Master (sagging pack)"
    override val id = "MASTER_SAG"
    override val bleName = "Master_VIRTUAL"

    private var random = Random(SEED)

    override fun reset() {
        random = Random(SEED)
    }

    override fun onWrite(data: ByteArray): List<ByteArray> {
        if (data.size == 1) {
            return when (data[0]) {
                'V'.code.toByte() -> listOf("GW2-MASTER-1.42".toByteArray(Charsets.US_ASCII))
                'N'.code.toByte() -> listOf("NAME=Master".toByteArray(Charsets.US_ASCII))
                else -> emptyList()
            }
        }
        return emptyList()
    }

    override fun onTick(elapsedMs: Long): List<ByteArray> {
        val minutes = elapsedMs / 60_000f
        val resting = (START_PCT - DISCHARGE_PCT_PER_MIN * minutes).coerceAtLeast(FLOOR_PCT)

        // Where in the 30 s throttle cycle this frame falls. The first second
        // of load ramps the sag in rather than stepping it, which is what a
        // motor drawing current into a pack actually looks like.
        val inCycle = (elapsedMs % CYCLE_MS) / 1000f
        val load = when {
            inCycle < LOAD_S -> minOf(1f, inCycle / 1f)
            else -> 0f
        }
        val sag = SAG_PCT * load
        val jitter = (random.nextFloat() * 2f - 1f) * JITTER_PCT
        val shown = (resting - sag + jitter).coerceIn(1f, 100f)

        // The Begode "better percents" curve, inverted: rawCv on the 84 V
        // reference scale, so the parser lands on exactly the percent above.
        val rawCv = (5320f + shown * 13.6f).toInt()
        val amps = if (load > 0f) COAST_AMPS + (LOAD_AMPS - COAST_AMPS) * load else COAST_AMPS
        val kmh = if (load > 0f) 25f else 15f
        val rawSpeed = (kmh * 100f / 3.6f).toInt()

        return listOf(
            begodeLiveFrame(
                elapsedMs = elapsedMs,
                rawCv = rawCv,
                rawSpeed = rawSpeed,
                rawCurrent = (amps * 100f).toInt(),
                rawTempReg = 0x0EFF,
            )
        )
    }

    private companion object {
        const val SEED = 20260904
        const val START_PCT = 36f
        const val FLOOR_PCT = 5f
        const val DISCHARGE_PCT_PER_MIN = 1f
        const val CYCLE_MS = 30_000L
        const val LOAD_S = 20f
        const val SAG_PCT = 10f
        const val JITTER_PCT = 1.5f
        const val LOAD_AMPS = 60f
        const val COAST_AMPS = 2f
    }
}
