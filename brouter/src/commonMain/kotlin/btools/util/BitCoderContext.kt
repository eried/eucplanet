package btools.util

import kotlin.jvm.JvmField

open class BitCoderContext(ab: ByteArray) {
  private var ab: ByteArray = ab
  private var idxMax: Int = ab.size - 1
  private var idx = -1
  private var bits = 0 // bits left in buffer
  private var b = 0 // buffer word

  companion object {
    @JvmField
    val vl_values = IntArray(4096)

    @JvmField
    val vl_length = IntArray(4096)

    private val vc_values = IntArray(4096)
    private val vc_length = IntArray(4096)

    private val reverse_byte = IntArray(256)

    private val bm2bits = IntArray(256)

    init {
      // fill varbits lookup table

      val bc = BitCoderContext(ByteArray(4))
      for (i in 0 until 4096) {
        bc.reset()
        bc.bits = 14
        bc.b = 0x1000 + i

        val b0 = bc.getReadingBitPosition()
        vl_values[i] = bc.decodeVarBits2()
        vl_length[i] = bc.getReadingBitPosition() - b0
      }
      for (i in 0 until 4096) {
        bc.reset()
        val b0 = bc.getWritingBitPosition()
        bc.encodeVarBits2(i)
        vc_values[i] = bc.b
        vc_length[i] = bc.getWritingBitPosition() - b0
      }
      for (i in 0 until 1024) {
        bc.reset()
        bc.bits = 14
        bc.b = 0x1000 + i

        val b0 = bc.getReadingBitPosition()
        vl_values[i] = bc.decodeVarBits2()
        vl_length[i] = bc.getReadingBitPosition() - b0
      }
      for (b in 0 until 256) {
        var r = 0
        for (i in 0 until 8) {
          if ((b and (1 shl i)) != 0) r = r or (1 shl (7 - i))
        }
        reverse_byte[b] = r
      }
      for (b in 0 until 8) {
        bm2bits[1 shl b] = b
      }
    }
  }

  fun reset(ab: ByteArray) {
    this.ab = ab
    idxMax = ab.size - 1
    reset()
  }

  fun reset() {
    idx = -1
    bits = 0
    b = 0
  }

  /**
   * encode a distance with a variable bit length
   * (poor mans huffman tree)
   * {@code 1 -> 0}
   * {@code 01 -> 1} + following 1-bit word ( 1..2 )
   * {@code 001 -> 3} + following 2-bit word ( 3..6 )
   * {@code 0001 -> 7} + following 3-bit word ( 7..14 ) etc.
   *
   * @see #decodeVarBits
   */
  fun encodeVarBits2(value: Int) {
    var value = value
    var range = 0
    while (value > range) {
      encodeBit(false)
      value -= range + 1
      range = 2 * range + 1
    }
    encodeBit(true)
    encodeBounded(range, value)
  }

  fun encodeVarBits(value: Int) {
    if ((value and 0xfff) == value) {
      flushBuffer()
      b = b or (vc_values[value] shl bits)
      bits += vc_length[value]
    } else {
      encodeVarBits2(value) // slow fallback for large values
    }
  }

  /**
   * @see #encodeVarBits
   */
  fun decodeVarBits2(): Int {
    var range = 0
    while (!decodeBit()) {
      range = 2 * range + 1
    }
    return range + decodeBounded(range)
  }

  fun decodeVarBits(): Int {
    fillBuffer()
    val b12 = b and 0xfff
    val len = vl_length[b12]
    if (len <= 12) {
      b = b ushr len
      bits -= len
      return vl_values[b12] // full value lookup
    }
    if (len <= 23) { // // only length lookup
      val len2 = len shr 1
      b = b ushr (len2 + 1)
      var mask = -1 ushr (32 - len2)
      mask += b and mask
      b = b ushr len2
      bits -= len
      return mask
    }
    if ((b and 0xffffff) != 0) {
      // here we just know len in [25..47]
      // ( fillBuffer guarantees only 24 bits! )
      b = b ushr 12
      val len3 = 1 + (vl_length[b and 0xfff] shr 1)
      b = b ushr len3
      val len2 = 11 + len3
      bits -= len2 + 1
      fillBuffer()
      var mask = -1 ushr (32 - len2)
      mask += b and mask
      b = b ushr len2
      bits -= len2
      return mask
    }
    return decodeVarBits2() // no chance, use the slow one
  }

  fun encodeBit(value: Boolean) {
    if (bits > 31) {
      ab[++idx] = (b and 0xff).toByte()
      b = b ushr 8
      bits -= 8
    }
    if (value) {
      b = b or (1 shl bits)
    }
    bits++
  }

  fun decodeBit(): Boolean {
    if (bits == 0) {
      bits = 8
      b = ab[++idx].toInt() and 0xff
    }
    val value = ((b and 1) != 0)
    b = b ushr 1
    bits--
    return value
  }

  /**
   * encode an integer in the range 0..max (inclusive).
   * For max = 2^n-1, this just encodes n bits, but in general
   * this is variable length encoding, with the shorter codes
   * for the central value range
   */
  fun encodeBounded(max: Int, value: Int) {
    var max = max
    var im = 1 // integer mask
    while (im <= max) {
      if ((value and im) != 0) {
        encodeBit(true)
        max -= im
      } else {
        encodeBit(false)
      }
      im = im shl 1
    }
  }

  /**
   * decode an integer in the range 0..max (inclusive).
   *
   * @see #encodeBounded
   */
  fun decodeBounded(max: Int): Int {
    var value = 0
    var im = 1 // integer mask
    while ((value or im) <= max) {
      if (bits == 0) {
        bits = 8
        b = ab[++idx].toInt() and 0xff
      }
      if ((b and 1) != 0)
        value = value or im
      b = b ushr 1
      bits--
      im = im shl 1
    }
    return value
  }

  fun decodeBits(count: Int): Int {
    fillBuffer()
    val mask = -1 ushr (32 - count)
    val value = b and mask
    b = b ushr count
    bits -= count
    return value
  }

  fun decodeBitsReverse(count: Int): Int {
    var count = count
    fillBuffer()
    var value = 0
    while (count > 8) {
      value = (value shl 8) or reverse_byte[b and 0xff]
      b = b shr 8
      count -= 8
      bits -= 8
      fillBuffer()
    }
    value = (value shl count) or (reverse_byte[b and 0xff] shr (8 - count))
    bits -= count
    b = b shr count
    return value
  }

  private fun fillBuffer() {
    while (bits < 24) {
      if (idx++ < idxMax) {
        b = b or ((ab[idx].toInt() and 0xff) shl bits)
      }
      bits += 8
    }
  }

  private fun flushBuffer() {
    while (bits > 7) {
      ab[++idx] = (b and 0xff).toByte()
      b = b ushr 8
      bits -= 8
    }
  }

  /**
   * flushes and closes the (write-mode) context
   *
   * @return the encoded length in bytes
   */
  fun closeAndGetEncodedLength(): Int {
    flushBuffer()
    if (bits > 0) {
      ab[++idx] = (b and 0xff).toByte()
    }
    return idx + 1
  }

  /**
   * @return the encoded length in bits
   */
  fun getWritingBitPosition(): Int {
    return (idx shl 3) + 8 + bits
  }

  fun getReadingBitPosition(): Int {
    return (idx shl 3) + 8 - bits
  }

  fun setReadingBitPosition(pos: Int) {
    idx = pos ushr 3
    bits = (idx shl 3) + 8 - pos
    b = ab[idx].toInt() and 0xff
    b = b ushr (8 - bits)
  }
}
