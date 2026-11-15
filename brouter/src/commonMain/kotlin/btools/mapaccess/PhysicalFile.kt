/**
 * cache for a single square
 *
 * @author ab
 */
package btools.mapaccess

import btools.codec.DataBuffers
import btools.codec.MicroCache
import btools.util.BFiles
import btools.util.ByteDataReader
import btools.util.Crc32
import btools.util.IOException
import btools.util.RandomReadFile
import kotlin.jvm.JvmField

class PhysicalFile(f: String, dataBuffers: DataBuffers, lookupVersion: Int, lookupMinorVersion: Int) {
  @JvmField internal var ra: RandomReadFile? = null
  @JvmField internal var fileIndex: LongArray = LongArray(25)
  @JvmField internal var fileHeaderCrcs: IntArray? = null

  @JvmField var creationTime: Long = 0

  @JvmField internal var fileName: String? = null

  @JvmField var divisor: Int = 80
  @JvmField var elevationType: Byte = 3

  init {
    fileName = btools.util.fileName(f)
    val iobuffer = dataBuffers.iobuffer!!
    val ra = BFiles.openRandom(f)
    this.ra = ra
    ra.readFully(0L, iobuffer, 0, 200)
    val fileIndexCrc = Crc32.crc(iobuffer, 0, 200)
    var dis = ByteDataReader(iobuffer)
    for (i in 0 until 25) {
      val lv = dis.readLong()
      val readVersion = (lv shr 48).toShort()
      if (i == 0 && lookupVersion != -1 && readVersion.toInt() != lookupVersion) {
        throw IOException("lookup version mismatch (old rd5?) lookups.dat=" +
          lookupVersion + " " + btools.util.fileName(f) + "=" + readVersion)
      }
      fileIndex[i] = lv and 0xffffffffffffL
    }

    // read some extra info from the end of the file, if present
    val len = ra.length()

    val pos = fileIndex[24]
    var extraLen = 8 + 26 * 4

    // Java returned from the constructor here when len == pos (old format o.k.)
    if (len != pos) {
      if ((len - pos) > extraLen) {
        extraLen++
      }

      if (len < pos + extraLen) { // > is o.k. for future extensions!
        throw IOException("file of size " + len + " too short, should be " + (pos + extraLen))
      }

      ra.readFully(pos, iobuffer, 0, extraLen)
      dis = ByteDataReader(iobuffer)
      creationTime = dis.readLong()

      val crcData = dis.readInt()
      if (crcData == fileIndexCrc) {
        divisor = 80 // old format
      } else if ((crcData xor 2) == fileIndexCrc) {
        divisor = 32 // new format
      } else {
        throw IOException("top index checksum error")
      }
      val fileHeaderCrcs = IntArray(25)
      this.fileHeaderCrcs = fileHeaderCrcs
      for (i in 0 until 25) {
        fileHeaderCrcs[i] = dis.readInt()
      }
      try {
        elevationType = dis.readByte()
      } catch (e: Exception) {
      }
    }
  }

  fun close() {
    if (ra != null) {
      try {
        ra!!.close()
      } catch (ee: Exception) {
      }
    }
  }

  companion object {
    fun checkVersionIntegrity(f: String): Int {
      var version = -1
      var raf: RandomReadFile? = null
      try {
        val iobuffer = ByteArray(200)
        raf = BFiles.openRandom(f)
        raf.readFully(0L, iobuffer, 0, 200)
        val dis = ByteDataReader(iobuffer)
        val lv = dis.readLong()
        version = (lv shr 48).toInt()
      } catch (e: IOException) {
      } finally {
        try {
          raf?.close()
        } catch (e: IOException) {
          throw RuntimeException(e)
        }
      }
      return version
    }

    /**
     * Checks the integrity of the file using the build-in checksums
     *
     * @return the error message if file corrupt, else null
     */
    fun checkFileIntegrity(f: String): String? {
      var pf: PhysicalFile? = null
      try {
        val dataBuffers = DataBuffers()
        pf = PhysicalFile(f, dataBuffers, -1, -1)
        val div = pf.divisor
        for (lonDegree in 0 until 5) { // doesn't really matter..
          for (latDegree in 0 until 5) { // ..where on earth we are
            val osmf = OsmFile(pf, lonDegree, latDegree, dataBuffers)
            if (osmf.hasData())
              for (lonIdx in 0 until div)
                for (latIdx in 0 until div)
                  osmf.createMicroCache(lonDegree * div + lonIdx, latDegree * div + latIdx, dataBuffers, null, null, MicroCache.debug, null)
          }
        }
      } finally {
        if (pf != null)
          try {
            pf.ra!!.close()
          } catch (ee: Exception) {
          }
      }
      return null
    }
  }

}
