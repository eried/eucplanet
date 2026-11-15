package btools.codec

import btools.util.BitCoderContext
import kotlin.jvm.JvmField

/**
 * Container for some re-usable databuffers for the decoder
 */
class DataBuffers {
  @JvmField
  var iobuffer: ByteArray

  @JvmField
  var tagbuf1 = ByteArray(256)

  @JvmField
  var bctx1 = BitCoderContext(tagbuf1)

  @JvmField
  var bbuf1 = ByteArray(65636)

  @JvmField
  var ibuf1 = IntArray(4096)

  @JvmField
  var ibuf2 = IntArray(2048)

  @JvmField
  var ibuf3 = IntArray(2048)

  @JvmField
  var alon = IntArray(2048)

  @JvmField
  var alat = IntArray(2048)

  constructor() : this(ByteArray(65636))

  /**
   * construct a set of databuffers except
   * for 'iobuffer', where the given array is used
   */
  constructor(iobuffer: ByteArray) {
    this.iobuffer = iobuffer
  }
}
