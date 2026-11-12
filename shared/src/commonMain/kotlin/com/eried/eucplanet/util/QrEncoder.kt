package com.eried.eucplanet.util

/**
 * A QR code encoder in plain Kotlin, for the phone that has no ZXing (iOS).
 *
 * It makes the same choices ZXing's `QRCodeWriter` makes with no character set
 * hint (the mode from the content, ISO-8859-1 bytes, the smallest version that
 * fits, the mask with the lowest ZXing penalty), so a code drawn from it is
 * module for module the one Android draws (`QrEncoderZxingTest`).
 */
object QrEncoder {
    enum class Ecc(internal val ordinalInTables: Int, internal val formatBits: Int) {
        L(0, 1), M(1, 0), Q(2, 3), H(3, 2)
    }

    /** The modules, `size` x `size`, row by row; true is dark. No quiet zone. */
    class Matrix(val size: Int, private val cells: BooleanArray, /** The mask pattern picked, 0 to 7. */ val mask: Int = 0) {
        operator fun get(x: Int, y: Int): Boolean = cells[y * size + x]
    }

    private enum class Mode(val bits: Int, val countBits: IntArray) {
        NUMERIC(0x1, intArrayOf(10, 12, 14)),
        ALPHANUMERIC(0x2, intArrayOf(9, 11, 13)),
        BYTE(0x4, intArrayOf(8, 16, 16));

        fun countBitsFor(version: Int) = countBits[if (version <= 9) 0 else if (version <= 26) 1 else 2]
    }

    private const val ALPHANUMERIC_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    fun encode(content: String, ecc: Ecc = Ecc.Q): Matrix {
        val mode = chooseMode(content)
        val data = BitBuffer()
        when (mode) {
            Mode.NUMERIC -> appendNumeric(content, data)
            Mode.ALPHANUMERIC -> appendAlphanumeric(content, data)
            Mode.BYTE -> latin1(content).forEach { data.append(it.toInt() and 0xFF, 8) }
        }
        val letters = if (mode == Mode.BYTE) data.size / 8 else content.length

        // As ZXing's recommendVersion: size it at version 1, then again at that guess.
        val provisional = chooseVersion(4 + mode.countBitsFor(1) + data.size, ecc)
        val version = chooseVersion(4 + mode.countBitsFor(provisional) + data.size, ecc)

        val bits = BitBuffer()
        bits.append(mode.bits, 4)
        bits.append(letters, mode.countBitsFor(version))
        bits.appendAll(data)

        val totalBytes = rawDataModules(version) / 8
        val blocks = ECC_BLOCKS[ecc.ordinalInTables][version]
        val ecPerBlock = ECC_PER_BLOCK[ecc.ordinalInTables][version]
        val dataBytes = totalBytes - blocks * ecPerBlock
        terminate(bits, dataBytes)
        val codewords = interleave(bits.toBytes(), totalBytes, dataBytes, blocks)

        val side = version * 4 + 17
        var best: IntArray? = null
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            val m = buildMatrix(codewords, ecc, version, mask, side)
            val penalty = penalty(m, side)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                best = m
            }
        }
        val cells = best!!
        return Matrix(side, BooleanArray(side * side) { cells[it] == 1 }, bestMask)
    }

    // ---- Data -------------------------------------------------------------

    private fun chooseMode(content: String): Mode {
        var numeric = false
        var alpha = false
        for (c in content) {
            when {
                c in '0'..'9' -> numeric = true
                ALPHANUMERIC_CHARS.indexOf(c) >= 0 -> alpha = true
                else -> return Mode.BYTE
            }
        }
        return if (alpha) Mode.ALPHANUMERIC else if (numeric) Mode.NUMERIC else Mode.BYTE
    }

    /** Java's ISO-8859-1 encoder: one byte per code point, '?' when it has none. */
    private fun latin1(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.add('?'.code.toByte()); i += 2; continue
            }
            out.add(if (c.code <= 0xFF) c.code.toByte() else '?'.code.toByte())
            i++
        }
        return out.toByteArray()
    }

    private fun appendNumeric(s: String, bits: BitBuffer) {
        var i = 0
        while (i < s.length) {
            val n1 = s[i] - '0'
            if (i + 2 < s.length) {
                bits.append(n1 * 100 + (s[i + 1] - '0') * 10 + (s[i + 2] - '0'), 10); i += 3
            } else if (i + 1 < s.length) {
                bits.append(n1 * 10 + (s[i + 1] - '0'), 7); i += 2
            } else {
                bits.append(n1, 4); i++
            }
        }
    }

    private fun appendAlphanumeric(s: String, bits: BitBuffer) {
        var i = 0
        while (i < s.length) {
            val c1 = ALPHANUMERIC_CHARS.indexOf(s[i])
            if (i + 1 < s.length) {
                bits.append(c1 * 45 + ALPHANUMERIC_CHARS.indexOf(s[i + 1]), 11); i += 2
            } else {
                bits.append(c1, 6); i++
            }
        }
    }

    private fun chooseVersion(bits: Int, ecc: Ecc): Int {
        for (v in 1..40) {
            val total = rawDataModules(v) / 8
            val data = total - ECC_BLOCKS[ecc.ordinalInTables][v] * ECC_PER_BLOCK[ecc.ordinalInTables][v]
            if (data >= (bits + 7) / 8) return v
        }
        throw IllegalArgumentException("Data too big for a QR code")
    }

    private fun terminate(bits: BitBuffer, dataBytes: Int) {
        val capacity = dataBytes * 8
        var i = 0
        while (i < 4 && bits.size < capacity) { bits.append(0, 1); i++ }
        val rem = bits.size and 7
        if (rem > 0) bits.append(0, 8 - rem)
        var pad = 0
        while (bits.size < capacity) { bits.append(if (pad % 2 == 0) 0xEC else 0x11, 8); pad++ }
    }

    private fun interleave(data: IntArray, totalBytes: Int, dataBytes: Int, blocks: Int): IntArray {
        val longBlocks = totalBytes % blocks
        val shortBlocks = blocks - longBlocks
        val shortTotal = totalBytes / blocks
        val shortData = dataBytes / blocks
        val ecLen = shortTotal - shortData
        val dataParts = ArrayList<IntArray>()
        val ecParts = ArrayList<IntArray>()
        var offset = 0
        for (b in 0 until blocks) {
            val len = if (b < shortBlocks) shortData else shortData + 1
            val part = data.copyOfRange(offset, offset + len)
            offset += len
            dataParts += part
            ecParts += reedSolomon(part, ecLen)
        }
        val out = ArrayList<Int>(totalBytes)
        val maxData = dataParts.maxOf { it.size }
        for (i in 0 until maxData) dataParts.forEach { if (i < it.size) out += it[i] }
        for (i in 0 until ecLen) ecParts.forEach { out += it[i] }
        return out.toIntArray()
    }

    // ---- Reed-Solomon over GF(256), polynomial 0x11D -------------------------

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    private fun reedSolomon(data: IntArray, ecLen: Int): IntArray {
        // Generator (x - a^0)(x - a^1)...(x - a^(n-1)), highest power first.
        var gen = intArrayOf(1)
        for (i in 0 until ecLen) {
            val next = IntArray(gen.size + 1)
            for (j in gen.indices) {
                next[j] = next[j] xor gen[j]
                next[j + 1] = next[j + 1] xor mul(gen[j], EXP[i])
            }
            gen = next
        }
        val rem = IntArray(ecLen)
        for (d in data) {
            val factor = d xor rem[0]
            for (j in 0 until ecLen - 1) rem[j] = rem[j + 1] xor mul(gen[j + 1], factor)
            rem[ecLen - 1] = mul(gen[ecLen], factor)
        }
        return rem
    }

    // ---- The matrix ---------------------------------------------------------

    private fun buildMatrix(codewords: IntArray, ecc: Ecc, version: Int, mask: Int, side: Int): IntArray {
        val m = IntArray(side * side) { -1 }
        fun set(x: Int, y: Int, v: Int) { m[y * side + x] = v }
        fun get(x: Int, y: Int) = m[y * side + x]

        // Finder patterns and their separators.
        for ((fx, fy) in listOf(0 to 0, side - 7 to 0, 0 to side - 7)) {
            for (dy in -1..7) for (dx in -1..7) {
                val x = fx + dx
                val y = fy + dy
                if (x !in 0 until side || y !in 0 until side) continue
                val inRing = dx in 0..6 && dy in 0..6 &&
                    (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4))
                set(x, y, if (inRing) 1 else 0)
            }
        }
        // The dark module.
        set(8, side - 8, 1)
        // Alignment patterns.
        val align = alignmentPositions(version)
        for (ay in align) for (ax in align) {
            if (get(ax, ay) != -1) continue
            for (dy in -2..2) for (dx in -2..2) {
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                set(ax + dx, ay + dy, if (ring == 1) 0 else 1)
            }
        }
        // Timing patterns.
        for (i in 8 until side - 8) {
            val bit = (i + 1) % 2
            if (get(i, 6) == -1) set(i, 6, bit)
            if (get(6, i) == -1) set(6, i, bit)
        }
        // Format information.
        val typeInfo = (ecc.formatBits shl 3) or mask
        val format = ((typeInfo shl 10) or bch(typeInfo, 0x537)) xor 0x5412
        for (i in 0 until 15) {
            val bit = (format shr i) and 1
            val (x1, y1) = TYPE_INFO_COORDINATES[i]
            set(x1, y1, bit)
            if (i < 8) set(side - i - 1, 8, bit) else set(8, side - 7 + (i - 8), bit)
        }
        // Version information.
        if (version >= 7) {
            val info = (version shl 12) or bch(version, 0x1F25)
            var index = 0
            for (i in 0 until 6) for (j in 0 until 3) {
                val bit = (info shr index) and 1
                index++
                set(i, side - 11 + j, bit)
                set(side - 11 + j, i, bit)
            }
        }
        // Data, in ZXing's zigzag.
        val totalBits = codewords.size * 8
        var bitIndex = 0
        var direction = -1
        var x = side - 1
        var y = side - 1
        while (x > 0) {
            if (x == 6) x -= 1
            while (y in 0 until side) {
                for (i in 0 until 2) {
                    val xx = x - i
                    if (get(xx, y) != -1) continue
                    var bit = if (bitIndex < totalBits) {
                        (codewords[bitIndex / 8] shr (7 - bitIndex % 8)) and 1
                    } else 0
                    bitIndex++
                    if (maskBit(mask, xx, y)) bit = bit xor 1
                    set(xx, y, bit)
                }
                y += direction
            }
            direction = -direction
            y += direction
            x -= 2
        }
        return m
    }

    private val TYPE_INFO_COORDINATES = arrayOf(
        8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
        7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
    )

    private fun bch(value: Int, poly: Int): Int {
        fun msb(v: Int) = 32 - v.countLeadingZeroBits()
        val polyMsb = msb(poly)
        var v = value shl (polyMsb - 1)
        while (msb(v) >= polyMsb) v = v xor (poly shl (msb(v) - polyMsb))
        return v
    }

    private fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = (version * 8 + count * 3 + 5) / (count * 4 - 4) * 2
        val out = IntArray(count)
        out[0] = 6
        var pos = version * 4 + 17 - 7
        for (i in count - 1 downTo 1) { out[i] = pos; pos -= step }
        return out
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean {
        val v = when (mask) {
            0 -> (y + x) and 1
            1 -> y and 1
            2 -> x % 3
            3 -> (y + x) % 3
            4 -> ((y / 2) + (x / 3)) and 1
            5 -> { val t = y * x; (t and 1) + (t % 3) }
            6 -> { val t = y * x; ((t and 1) + (t % 3)) and 1 }
            else -> ((y * x % 3) + ((y + x) and 1)) and 1
        }
        return v == 0
    }

    // ---- ZXing's mask penalty (MaskUtil) ---------------------------------------

    private fun penalty(m: IntArray, side: Int): Int =
        rule1(m, side, true) + rule1(m, side, false) + rule2(m, side) + rule3(m, side) + rule4(m, side)

    private fun rule1(m: IntArray, side: Int, horizontal: Boolean): Int {
        var penalty = 0
        for (i in 0 until side) {
            var same = 0
            var prev = -1
            for (j in 0 until side) {
                val bit = if (horizontal) m[i * side + j] else m[j * side + i]
                if (bit == prev) {
                    same++
                } else {
                    if (same >= 5) penalty += 3 + (same - 5)
                    same = 1
                    prev = bit
                }
            }
            if (same >= 5) penalty += 3 + (same - 5)
        }
        return penalty
    }

    private fun rule2(m: IntArray, side: Int): Int {
        var penalty = 0
        for (y in 0 until side - 1) for (x in 0 until side - 1) {
            val v = m[y * side + x]
            if (v == m[y * side + x + 1] && v == m[(y + 1) * side + x] && v == m[(y + 1) * side + x + 1]) penalty++
        }
        return 3 * penalty
    }

    private fun rule3(m: IntArray, side: Int): Int {
        fun at(x: Int, y: Int) = m[y * side + x]
        // A run that would reach past the edge counts as not white, as in ZXing.
        fun whiteH(y: Int, from: Int, to: Int): Boolean {
            if (from < 0 || to > side) return false
            for (i in from until to) if (at(i, y) == 1) return false
            return true
        }
        fun whiteV(x: Int, from: Int, to: Int): Boolean {
            if (from < 0 || to > side) return false
            for (i in from until to) if (at(x, i) == 1) return false
            return true
        }
        var n = 0
        for (y in 0 until side) for (x in 0 until side) {
            if (x + 6 < side && at(x, y) == 1 && at(x + 1, y) == 0 && at(x + 2, y) == 1 && at(x + 3, y) == 1 &&
                at(x + 4, y) == 1 && at(x + 5, y) == 0 && at(x + 6, y) == 1 &&
                (whiteH(y, x - 4, x) || whiteH(y, x + 7, x + 11))
            ) n++
            if (y + 6 < side && at(x, y) == 1 && at(x, y + 1) == 0 && at(x, y + 2) == 1 && at(x, y + 3) == 1 &&
                at(x, y + 4) == 1 && at(x, y + 5) == 0 && at(x, y + 6) == 1 &&
                (whiteV(x, y - 4, y) || whiteV(x, y + 7, y + 11))
            ) n++
        }
        return n * 40
    }

    private fun rule4(m: IntArray, side: Int): Int {
        val dark = m.count { it == 1 }
        val total = side * side
        return kotlin.math.abs(dark * 2 - total) * 10 / total * 10
    }

    // ---- Capacity tables (ISO/IEC 18004 table 9), index 0 unused --------------

    private fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            result -= (25 * align - 10) * align - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )

    private val ECC_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits += ((value ushr i) and 1) == 1
        }
        fun appendAll(other: BitBuffer) { bits.addAll(other.bits) }
        fun toBytes(): IntArray = IntArray(bits.size / 8) { b ->
            var v = 0
            for (i in 0 until 8) v = (v shl 1) or (if (bits[b * 8 + i]) 1 else 0)
            v
        }
    }
}
