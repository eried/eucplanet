package btools.codec

/**
 * Special integer fifo suitable for 3-pass encoding
 */
open class IntegerFifo3Pass(capacity: Int) {
  private var a: IntArray = if (capacity < 4) IntArray(4) else IntArray(capacity)
  private var size = 0
  private var pos = 0

  private var pass = 0

  /**
   * Starts a new encoding pass and resets the reading pointer
   * from the stats collected in pass2 and writes that to the given context
   */
  fun init() {
    pass++
    pos = 0
  }

  /**
   * writes to the fifo in pass2
   */
  fun add(value: Int) {
    if (pass == 2) {
      if (size == a.size) {
        val aa = IntArray(2 * size)
        a.copyInto(aa, 0, 0, size)
        a = aa
      }
      a[size++] = value
    }
  }

  /**
   * reads from the fifo in pass3 (in pass1/2 returns just 1)
   */
  fun getNext(): Int {
    return if (pass == 3) get(pos++) else 1
  }

  private fun get(idx: Int): Int {
    if (idx >= size) {
      throw IndexOutOfBoundsException("list size=$size idx=$idx")
    }
    return a[idx]
  }
}
