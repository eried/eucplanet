package btools.codec

import btools.util.BitCoderContext

class StatCoderContext(ab: ByteArray) : BitCoderContext(ab) {

  companion object {
    private val noisy_bits = IntArray(1024)

    init {
      // noisybits lookup
      for (i in 0 until 1024) {
        var p = i
        var noisybits = 0
        while (p > 2) {
          noisybits++
          p = p shr 1
        }
        noisy_bits[i] = noisybits
      }
    }
  }

  /**
   * decode an unsigned integer with some of of least significant bits
   * considered noisy
   *
   * @see #encodeNoisyNumber
   */
  fun decodeNoisyNumber(noisybits: Int): Int {
    val value = decodeBits(noisybits)
    return value or (decodeVarBits() shl noisybits)
  }

  /**
   * decode a signed integer with some of of least significant bits considered
   * noisy
   *
   * @see #encodeNoisyDiff
   */
  fun decodeNoisyDiff(noisybits: Int): Int {
    var value = 0
    if (noisybits > 0) {
      value = decodeBits(noisybits) - (1 shl (noisybits - 1))
    }
    var val2 = decodeVarBits() shl noisybits
    if (val2 != 0) {
      if (decodeBit()) {
        val2 = -val2
      }
    }
    return value + val2
  }

  /**
   * decode a signed integer with the typical range and median taken from the
   * predicted value
   *
   * @see #encodePredictedValue
   */
  fun decodePredictedValue(predictor: Int): Int {
    var p = if (predictor < 0) -predictor else predictor
    var noisybits = 0
    while (p > 1023) {
      noisybits++
      p = p shr 1
    }
    return predictor + decodeNoisyDiff(noisybits + noisy_bits[p])
  }

  /**
   * @param values  the array to encode
   * @param offset  position in this array where to start
   * @param subsize number of values to encode
   * @param nextbit bitmask with the most significant bit set to 1
   * @param value   should be 0
   * @see #encodeSortedArray
   */
  fun decodeSortedArray(values: IntArray, offset: Int, subsize: Int, nextbitpos: Int, value: Int) {
    var offset = offset
    var subsize = subsize
    var value = value
    if (subsize == 1) { // last-choice shortcut
      if (nextbitpos >= 0) {
        value = value or decodeBitsReverse(nextbitpos + 1)
      }
      values[offset] = value
      return
    }
    if (nextbitpos < 0) {
      while (subsize-- > 0) {
        values[offset++] = value
      }
      return
    }

    val size1 = decodeBounded(subsize)
    val size2 = subsize - size1

    if (size1 > 0) {
      decodeSortedArray(values, offset, size1, nextbitpos - 1, value)
    }
    if (size2 > 0) {
      decodeSortedArray(values, offset + size1, size2, nextbitpos - 1, value or (1 shl nextbitpos))
    }
  }
}
