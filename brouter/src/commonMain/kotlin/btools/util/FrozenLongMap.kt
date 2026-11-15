package btools.util

/**
 * Frozen instance of Memory efficient Map
 * <p>
 * This one is readily sorted into a singe array for faster access
 *
 * @author ab
 */
open class FrozenLongMap<V>(map: CompactLongMap<V>) : CompactLongMap<V>() {
  private val faid: LongArray
  private val flv: MutableList<V>
  private var size = 0
  private var p2size: Int // next power of 2 of size

  init {
    size = map.size()

    faid = LongArray(size)
    flv = ArrayList(size)

    map.moveToFrozenArrays(faid, flv)

    p2size = 0x40000000
    while (p2size > size) p2size = p2size shr 1
  }

  override fun put(id: Long, value: V): Boolean {
    try {
      value_in = value
      if (contains(id, true)) {
        return true
      }
      throw RuntimeException("cannot only put on existing key in FrozenLongIntMap")
    } finally {
      value_in = null
      value_out = null
    }
  }

  override fun fastPut(id: Long, value: V) {
    throw RuntimeException("cannot put on FrozenLongIntMap")
  }

  /**
   * @return the number of entries in this set
   */
  override fun size(): Int {
    return size
  }

  /**
   * @return true if "id" is contained in this set.
   */
  @Suppress("UNCHECKED_CAST")
  override fun contains(id: Long, doPut: Boolean): Boolean {
    if (size == 0) {
      return false
    }
    val a = faid
    var offset = p2size
    var n = 0

    while (offset > 0) {
      val nn = n + offset
      if (nn < size && a[nn] <= id) {
        n = nn
      }
      offset = offset shr 1
    }
    if (a[n] == id) {
      value_out = flv[n]
      if (doPut) {
        flv[n] = value_in as V
      }
      return true
    }
    return false
  }

  /**
   * @return the value for "id", or null if key unknown
   */
  override fun get(id: Long): V? {
    if (size == 0) {
      return null
    }
    val a = faid
    var offset = p2size
    var n = 0

    while (offset > 0) {
      val nn = n + offset
      if (nn < size && a[nn] <= id) {
        n = nn
      }
      offset = offset shr 1
    }
    if (a[n] == id) {
      return flv[n]
    }
    return null
  }

  fun getValueList(): MutableList<V> {
    return flv
  }

  fun getKeyArray(): LongArray {
    return faid
  }
}
