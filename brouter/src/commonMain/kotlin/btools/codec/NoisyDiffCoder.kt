package btools.codec

/**
 * Encoder/Decoder for signed integers that automatically detects the typical
 * range of these numbers to determine a noisy-bit count as a very simple
 * dictionary
 * <p>
 * Adapted for 3-pass encoding (counters -&gt; statistics -&gt; encoding )
 * but doesn't do anything at pass1
 */
class NoisyDiffCoder(bc: StatCoderContext) {
  private var noisybits: Int
  private var bc: StatCoderContext

  /**
   * Create a decoder and read the noisy-bit count from the gibe context
   */
  init {
    noisybits = bc.decodeVarBits()
    this.bc = bc
  }

  /**
   * decodes a signed int
   */
  fun decodeSignedValue(): Int {
    return bc.decodeNoisyDiff(noisybits)
  }
}
