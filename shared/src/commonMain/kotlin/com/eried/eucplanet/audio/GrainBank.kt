package com.eried.eucplanet.audio

/**
 * A recorded engine, cut into grains by tools/engine-sounds/grains.py.
 *
 * Each grain is two pattern periods of the recording (a pattern is one crank
 * revolution, or one chuff for the steam engine), Hann windowed and centred on
 * the same point of the cycle as its neighbours. Laid end to end one period
 * apart, a run of grains gives the recording back; laid closer together, the
 * engine fires faster while its pipes keep their real pitch. The grains are
 * grouped into [bands] by the pattern frequency the recording was running at,
 * lowest first, so the player can pick the band made at about the speed asked.
 *
 * Samples are resampled to the player's rate when the bank is read, so the
 * audio thread only ever adds arrays together.
 */
class GrainBank(
    /** Grains per crank revolution: 1 for a piston engine, 4 for the steam engine's chuffs. */
    val patternPerRev: Float,
    val bands: List<Band>,
    /** The engine starting, played once when the sound comes on. Empty when the recording has none. */
    val startup: FloatArray,
    /** Backfires and pops from the same recording, one is picked at random per pop. */
    val pops: List<FloatArray>,
) {
    class Band(
        /** Pattern frequency of the recording in this band, Hz. */
        val hz: Float,
        /** Runs of consecutive grains; a grain follows the one before it in the recording. */
        val runs: List<Array<FloatArray>>,
    )

    val lowestHz: Float get() = bands.first().hz

    /** A bank file that does not read: not a bank, a version this player does not know, or cut short. */
    class FormatException(message: String) : Exception(message)

    /** Little-endian reads over the file's bytes; any read past the end is a [FormatException]. */
    private class Reader(private val b: ByteArray) {
        var pos = 0
            private set
        val remaining: Int get() = b.size - pos

        private fun need(n: Int) { if (remaining < n) throw FormatException("truncated grain bank") }
        fun byte(): Int { need(1); return b[pos++].toInt() }
        fun short(): Short {
            need(2)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v.toShort()
        }
        fun int(): Int {
            need(4)
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
                ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }
        fun intBigEndian(): Int {
            need(4)
            val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        fun float(): Float = Float.fromBits(int())
    }

    companion object {
        private const val MAGIC = 0x45554347   // "EUCG"

        /**
         * Reads a bank written by grains.py and resamples it to [outRate].
         * Throws [FormatException] on a malformed file.
         */
        fun read(bytes: ByteArray, outRate: Int): GrainBank {
            val buf = Reader(bytes)
            if (bytes.size < 16 || buf.intBigEndian() != MAGIC) throw FormatException("not a grain bank")
            val version = buf.short().toInt()
            if (version != 1) throw FormatException("grain bank version $version")
            val rate = buf.short().toInt() and 0xFFFF
            val patternPerRev = buf.float()
            val bandCount = buf.short().toInt() and 0xFFFF
            val shotCount = buf.short().toInt() and 0xFFFF
            val ratio = rate.toDouble() / outRate
            fun pcm(count: Int): FloatArray {
                if (count < 0 || buf.remaining < count * 2) throw FormatException("truncated grain bank")
                val src = FloatArray(count) { buf.short() / 32768f }
                return resample(src, ratio)
            }
            val bands = ArrayList<Band>(bandCount)
            repeat(bandCount) {
                val hz = buf.float()
                val runCount = buf.short().toInt() and 0xFFFF
                val runs = ArrayList<Array<FloatArray>>(runCount)
                repeat(runCount) {
                    val grainCount = buf.short().toInt() and 0xFFFF
                    runs += Array(grainCount) { pcm(buf.short().toInt() and 0xFFFF) }
                }
                bands += Band(hz, runs)
            }
            var startup = FloatArray(0)
            val pops = ArrayList<FloatArray>()
            repeat(shotCount) {
                val kind = buf.byte()
                val samples = pcm(buf.int())
                when (kind) {
                    0 -> startup = samples
                    2 -> pops += samples
                }
            }
            if (bands.isEmpty()) throw FormatException("grain bank has no bands")
            return GrainBank(patternPerRev, bands.sortedBy { it.hz }, startup, pops)
        }

        /** Linear interpolation; the recordings carry nothing near their Nyquist that the speaker would show. */
        private fun resample(src: FloatArray, ratio: Double): FloatArray {
            if (ratio == 1.0) return src
            val n = (src.size / ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(n)
            for (i in 0 until n) {
                val x = i * ratio
                val k = x.toInt()
                val f = (x - k).toFloat()
                val a = src[k.coerceAtMost(src.size - 1)]
                val b = src[(k + 1).coerceAtMost(src.size - 1)]
                out[i] = a + (b - a) * f
            }
            return out
        }

        /** The bank in [open]'s bytes (Android resources, the iPhone's bundle, a test folder), or null when missing or broken. */
        fun load(outRate: Int, open: () -> ByteArray?): GrainBank? {
            val bytes = open() ?: return null
            return try { read(bytes, outRate) } catch (_: FormatException) { null }
        }
    }
}
