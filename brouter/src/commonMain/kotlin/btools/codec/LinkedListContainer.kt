package btools.codec

/**
 * Simple container for a list of lists of integers
 */
open class LinkedListContainer(nlists: Int, defaultbuffer: IntArray?) {
  private var ia: IntArray // prev, data, prev, data, ...
  private var size = 0
  private var startpointer: IntArray // 0=void, odd=head-data-cell
  private var listpointer = 0

  /**
   * Construct a container for the given number of lists
   * <p>
   * If no default-buffer is given, an int[nlists*4] is constructed,
   * able to hold 2 entries per list on average
   *
   * @param nlists        the number of lists
   * @param defaultbuffer an optional data array for re-use (gets replaced if too small)
   */
  init {
    ia = defaultbuffer ?: IntArray(nlists * 4)
    startpointer = IntArray(nlists)
  }

  /**
   * Add a data element to the given list
   *
   * @param listNr the list to add the data to
   * @param data   the data value
   */
  fun addDataElement(listNr: Int, data: Int) {
    if (size + 2 > ia.size) {
      resize()
    }
    ia[size++] = startpointer[listNr]
    startpointer[listNr] = size
    ia[size++] = data
  }

  /**
   * Initialize a list for reading
   *
   * @param listNr the list to initialize
   * @return the number of entries in that list
   */
  fun initList(listNr: Int): Int {
    var cnt = 0
    listpointer = startpointer[listNr]
    var lp = listpointer
    while (lp != 0) {
      lp = ia[lp - 1]
      cnt++
    }
    return cnt
  }

  /**
   * Get a data element from the list previously initialized.
   * Data elements are return in reverse order (lifo)
   *
   * @return the data element
   * @throws IllegalArgumentException if no more element
   */
  fun getDataElement(): Int {
    if (listpointer == 0) {
      throw IllegalArgumentException("no more element!")
    }
    val data = ia[listpointer]
    listpointer = ia[listpointer - 1]
    return data
  }

  private fun resize() {
    val ia2 = IntArray(2 * ia.size)
    ia.copyInto(ia2, 0, 0, ia.size)
    ia = ia2
  }
}
