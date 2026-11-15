/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess

import btools.codec.DataBuffers
import btools.codec.MicroCache
import btools.codec.MicroCache2
import btools.codec.StatCoderContext
import btools.codec.TagValueValidator
import btools.codec.WaypointMatcher
import btools.util.ByteDataReader
import btools.util.Crc32
import btools.util.IOException
import btools.util.RandomReadFile
import kotlin.jvm.JvmField

class OsmFile(rafile: PhysicalFile?, lonDegree: Int, latDegree: Int, dataBuffers: DataBuffers) {
  private var `is`: RandomReadFile? = null
  private var fileOffset: Long = 0

  private var posIdx: IntArray? = null
  private var microCaches: Array<MicroCache?>? = null

  @JvmField var lonDegree: Int = 0
  @JvmField var latDegree: Int = 0

  @JvmField var filename: String? = null

  private var divisor = 0
  private var cellsize = 0
  private var indexsize = 0
  @JvmField internal var elevationType: Byte = 3

  init {
    this.lonDegree = lonDegree
    this.latDegree = latDegree
    val lonMod5 = lonDegree % 5
    val latMod5 = latDegree % 5
    val tileIndex = lonMod5 * 5 + latMod5

    if (rafile != null) {
      divisor = rafile.divisor
      elevationType = rafile.elevationType

      cellsize = 1000000 / divisor
      val ncaches = divisor * divisor
      indexsize = ncaches * 4

      val iobuffer = dataBuffers.iobuffer!!
      filename = rafile.fileName

      val index = rafile.fileIndex
      fileOffset = if (tileIndex > 0) index[tileIndex - 1] else 200L
      // Java returned from the constructor here when fileOffset == index[tileIndex] (empty)
      if (fileOffset != index[tileIndex]) {
        val ra = rafile.ra!!
        `is` = ra
        val posIdx = IntArray(ncaches)
        this.posIdx = posIdx
        microCaches = arrayOfNulls(ncaches)
        ra.readFully(fileOffset, iobuffer, 0, indexsize)

        if (rafile.fileHeaderCrcs != null) {
          val headerCrc = Crc32.crc(iobuffer, 0, indexsize)
          if (rafile.fileHeaderCrcs!![tileIndex] != headerCrc) {
            throw IOException("sub index checksum error")
          }
        }

        val dis = ByteDataReader(iobuffer)
        for (i in 0 until ncaches) {
          posIdx[i] = dis.readInt()
        }
      }
    }
  }

  fun hasData(): Boolean {
    return microCaches != null
  }

  fun getMicroCache(ilon: Int, ilat: Int): MicroCache? {
    val lonIdx = ilon / cellsize
    val latIdx = ilat / cellsize
    val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)
    return microCaches!![subIdx]
  }

  fun createMicroCache(ilon: Int, ilat: Int, dataBuffers: DataBuffers, wayValidator: TagValueValidator?, waypointMatcher: WaypointMatcher?, hollowNodes: OsmNodesMap?): MicroCache? {
    val lonIdx = ilon / cellsize
    val latIdx = ilat / cellsize
    val segment = createMicroCache(lonIdx, latIdx, dataBuffers, wayValidator, waypointMatcher, true, hollowNodes)
    val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)
    microCaches!![subIdx] = segment
    return segment
  }

  private fun getPosIdx(idx: Int): Int {
    return if (idx == -1) indexsize else posIdx!![idx]
  }

  fun getDataInputForSubIdx(subIdx: Int, iobuffer: ByteArray): Int {
    val startPos = getPosIdx(subIdx - 1)
    val endPos = getPosIdx(subIdx)
    val size = endPos - startPos
    if (size > 0) {
      if (size <= iobuffer.size) {
        `is`!!.readFully(fileOffset + startPos, iobuffer, 0, size)
      }
    }
    return size
  }

  fun createMicroCache(lonIdx: Int, latIdx: Int, dataBuffers: DataBuffers, wayValidator: TagValueValidator?,
                       waypointMatcher: WaypointMatcher?, reallyDecode: Boolean, hollowNodes: OsmNodesMap?): MicroCache? {
    val subIdx = (latIdx - divisor * latDegree) * divisor + (lonIdx - divisor * lonDegree)

    var ab = dataBuffers.iobuffer!!
    var asize = getDataInputForSubIdx(subIdx, ab)

    if (asize == 0) {
      return MicroCache.emptyCache()
    }
    if (asize > ab.size) {
      ab = ByteArray(asize)
      asize = getDataInputForSubIdx(subIdx, ab)
    }

    val bc = StatCoderContext(ab)

    try {
      if (!reallyDecode) {
        return null
      }
      if (hollowNodes == null) {
        return MicroCache2(bc, dataBuffers, lonIdx, latIdx, divisor, wayValidator, waypointMatcher)
      }
      DirectWeaver(bc, dataBuffers, lonIdx, latIdx, divisor, wayValidator, waypointMatcher, hollowNodes)
      return MicroCache.emptyNonVirgin
    } finally {
      // crc check only if the buffer has not been fully read
      val readBytes = (bc.getReadingBitPosition() + 7) shr 3
      if (readBytes != asize - 4) {
        val crcData = Crc32.crc(ab, 0, asize - 4)
        val crcFooter = ByteDataReader(ab, asize - 4).readInt()
        if (crcData == crcFooter) {
          throw IOException("old, unsupported data-format")
        } else if ((crcData xor 2) != crcFooter) {
          throw IOException("checkum error")
        }
      }
    }
  }

  // set this OsmFile to ghost-state:
  internal fun setGhostState(): Long {
    var sum: Long = 0
    val microCaches = microCaches
    val nc = microCaches?.size ?: 0
    for (i in 0 until nc) {
      val mc = microCaches!![i] ?: continue
      if (mc.virgin) {
        mc.ghost = true
        sum += mc.getDataSize()
      } else {
        microCaches[i] = null
      }
    }
    return sum
  }

  internal fun collectAll(): Long {
    var deleted: Long = 0
    val microCaches = microCaches
    val nc = microCaches?.size ?: 0
    for (i in 0 until nc) {
      val mc = microCaches!![i] ?: continue
      if (!mc.ghost) {
        deleted += mc.collect(0)
      }
    }
    return deleted
  }

  internal fun cleanGhosts(): Long {
    val deleted: Long = 0
    val microCaches = microCaches
    val nc = microCaches?.size ?: 0
    for (i in 0 until nc) {
      val mc = microCaches!![i] ?: continue
      if (mc.ghost) {
        microCaches[i] = null
      }
    }
    return deleted
  }

  internal fun clean(all: Boolean) {
    val microCaches = microCaches
    val nc = microCaches?.size ?: 0
    for (i in 0 until nc) {
      val mc = microCaches!![i] ?: continue
      if (all || !mc.virgin) {
        microCaches[i] = null
      }
    }
  }
}
