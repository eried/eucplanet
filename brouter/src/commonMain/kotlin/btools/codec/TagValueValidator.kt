package btools.codec

interface TagValueValidator {
  /**
   * @param tagValueSet the way description to check
   * @return 0 = nothing, 1=no matching, 2=normal
   */
  fun accessType(tagValueSet: ByteArray): Int

  fun unify(tagValueSet: ByteArray, offset: Int, len: Int): ByteArray

  fun isLookupIdxUsed(idx: Int): Boolean

  fun setDecodeForbidden(decodeForbidden: Boolean)

  fun checkStartWay(ab: ByteArray?): Boolean
}
