package btools.codec

import btools.util.IByteArrayUnifier

/**
 * MicroCache2 is the new format that uses statistical encoding and
 * is able to do access filtering and waypoint matching during encoding
 */
class MicroCache2(
  bc: StatCoderContext,
  dataBuffers: DataBuffers,
  lonIdx: Int,
  latIdx: Int,
  divisor: Int,
  wayValidator: TagValueValidator?,
  waypointMatcher: WaypointMatcher?
) : MicroCache(null) {
  private var lonBase = 0
  private var latBase = 0
  private var cellsize = 0

  fun readUnified(len: Int, u: IByteArrayUnifier): ByteArray {
    val b = u.unify(ab!!, aboffset, len)
    aboffset += len
    return b
  }

  init {
    cellsize = 1000000 / divisor
    lonBase = lonIdx * cellsize
    latBase = latIdx * cellsize

    val wayTagCoder = TagValueCoder(bc, dataBuffers, wayValidator)
    val nodeTagCoder = TagValueCoder(bc, dataBuffers, null)
    val nodeIdxDiff = NoisyDiffCoder(bc)
    val nodeEleDiff = NoisyDiffCoder(bc)
    val extLonDiff = NoisyDiffCoder(bc)
    val extLatDiff = NoisyDiffCoder(bc)
    val transEleDiff = NoisyDiffCoder(bc)

    size = bc.decodeNoisyNumber(5)
    faid = if (size > dataBuffers.ibuf2.size) IntArray(size) else dataBuffers.ibuf2
    fapos = if (size > dataBuffers.ibuf3.size) IntArray(size) else dataBuffers.ibuf3

    val alon = if (size > dataBuffers.alon.size) IntArray(size) else dataBuffers.alon
    val alat = if (size > dataBuffers.alat.size) IntArray(size) else dataBuffers.alat

    bc.decodeSortedArray(faid!!, 0, size, 29, 0)

    for (n in 0 until size) {
      val id64 = expandId(faid!![n])
      alon[n] = (id64 shr 32).toInt()
      alat[n] = (id64 and 0xffffffffL).toInt()
    }

    val netdatasize = bc.decodeNoisyNumber(10)
    ab = if (netdatasize > dataBuffers.bbuf1.size) ByteArray(netdatasize) else dataBuffers.bbuf1
    aboffset = 0

    val validBits = IntArray((size + 31) shr 5)

    var finaldatasize = 0

    val reverseLinks = LinkedListContainer(size, dataBuffers.ibuf1)

    var selev = 0
    for (n in 0 until size) { // loop over nodes
      val ilon = alon[n]
      val ilat = alat[n]

      // future escapes (turn restrictions?)
      var trExceptions: Short = 0
      var featureId = bc.decodeVarBits()
      if (featureId == 13) {
        fapos!![n] = aboffset
        validBits[n shr 5] = validBits[n shr 5] or (1 shl n) // mark dummy-node valid
        continue // empty node escape (delta files only)
      }
      while (featureId != 0) {
        val bitsize = bc.decodeNoisyNumber(5)

        if (featureId == 2) { // exceptions to turn-restriction
          trExceptions = bc.decodeBounded(1023).toShort()
        } else if (featureId == 1) { // turn-restriction
          writeBoolean(true)
          writeShort(trExceptions.toInt()) // exceptions from previous feature
          trExceptions = 0

          writeBoolean(bc.decodeBit()) // isPositive
          writeInt(ilon + bc.decodeNoisyDiff(10)) // fromLon
          writeInt(ilat + bc.decodeNoisyDiff(10)) // fromLat
          writeInt(ilon + bc.decodeNoisyDiff(10)) // toLon
          writeInt(ilat + bc.decodeNoisyDiff(10)) // toLat
        } else {
          for (i in 0 until bitsize) bc.decodeBit() // unknown feature, just skip
        }
        featureId = bc.decodeVarBits()
      }
      writeBoolean(false)

      selev += nodeEleDiff.decodeSignedValue()
      writeShort(selev.toShort().toInt())
      val nodeTags = nodeTagCoder.decodeTagValueSet()
      writeVarBytes(if (nodeTags == null) null else nodeTags.data)

      val links = bc.decodeNoisyNumber(1)
      for (li in 0 until links) {
        var sizeoffset = 0
        val nodeIdx = n + nodeIdxDiff.decodeSignedValue()

        var dlon_remaining: Int
        var dlat_remaining: Int

        var isReverse = false
        if (nodeIdx != n) { // internal (forward-) link
          dlon_remaining = alon[nodeIdx] - ilon
          dlat_remaining = alat[nodeIdx] - ilat
        } else {
          isReverse = bc.decodeBit()
          dlon_remaining = extLonDiff.decodeSignedValue()
          dlat_remaining = extLatDiff.decodeSignedValue()
        }

        val wayTags = wayTagCoder.decodeTagValueSet()

        val linkValid = wayTags != null || wayValidator == null
        if (linkValid) {
          val startPointer = aboffset
          sizeoffset = writeSizePlaceHolder()

          writeVarLengthSigned(dlon_remaining)
          writeVarLengthSigned(dlat_remaining)

          validBits[n shr 5] = validBits[n shr 5] or (1 shl n) // mark source-node valid
          if (nodeIdx != n) { // valid internal (forward-) link
            reverseLinks.addDataElement(nodeIdx, n) // register reverse link
            finaldatasize += 1 + aboffset - startPointer // reserve place for reverse
            validBits[nodeIdx shr 5] = validBits[nodeIdx shr 5] or (1 shl nodeIdx) // mark target-node valid
          }
          writeModeAndDesc(isReverse, if (wayTags == null) null else wayTags.data)
        }

        if (!isReverse) { // write geometry for forward links only
          var matcher = if (wayTags == null || wayTags.accessType < 2) null else waypointMatcher
          val ilontarget = ilon + dlon_remaining
          val ilattarget = ilat + dlat_remaining
          if (matcher != null) {
            val useAsStartWay = wayValidator!!.checkStartWay(wayTags!!.data)
            if (!matcher.start(ilon, ilat, ilontarget, ilattarget, useAsStartWay)) {
              matcher = null
            }
          }

          val transcount = bc.decodeVarBits()
          var count = transcount + 1
          for (i in 0 until transcount) {
            val dlon = bc.decodePredictedValue(dlon_remaining / count)
            val dlat = bc.decodePredictedValue(dlat_remaining / count)
            dlon_remaining -= dlon
            dlat_remaining -= dlat
            count--
            val elediff = transEleDiff.decodeSignedValue()
            if (wayTags != null) {
              writeVarLengthSigned(dlon)
              writeVarLengthSigned(dlat)
              writeVarLengthSigned(elediff)
            }

            if (matcher != null)
              matcher.transferNode(ilontarget - dlon_remaining, ilattarget - dlat_remaining)
          }
          if (matcher != null) matcher.end()
        }
        if (linkValid) {
          injectSize(sizeoffset)
        }
      }
      fapos!![n] = aboffset
    }

    // calculate final data size
    var finalsize = 0
    var startpos = 0
    for (i in 0 until size) {
      val endpos = fapos!![i]
      if ((validBits[i shr 5] and (1 shl i)) != 0) {
        finaldatasize += endpos - startpos
        finalsize++
      }
      startpos = endpos
    }
    // append the reverse links at the end of each node
    val abOld = ab!!
    val faidOld = faid!!
    val faposOld = fapos!!
    val sizeOld = size
    val abNew = ByteArray(finaldatasize)
    val faidNew = IntArray(finalsize)
    val faposNew = IntArray(finalsize)
    ab = abNew
    faid = faidNew
    fapos = faposNew
    aboffset = 0
    size = 0

    startpos = 0
    for (n in 0 until sizeOld) {
      val endpos = faposOld[n]
      if ((validBits[n shr 5] and (1 shl n)) != 0) {
        val len = endpos - startpos
        abOld.copyInto(abNew, aboffset, startpos, startpos + len)
        aboffset += len

        val cnt = reverseLinks.initList(n)

        for (ri in 0 until cnt) {
          val nodeIdx = reverseLinks.getDataElement()
          val sizeoffset = writeSizePlaceHolder()
          writeVarLengthSigned(alon[nodeIdx] - alon[n])
          writeVarLengthSigned(alat[nodeIdx] - alat[n])
          writeModeAndDesc(true, null)
          injectSize(sizeoffset)
        }
        faidNew[size] = faidOld[n]
        faposNew[size] = aboffset
        size++
      }
      startpos = endpos
    }
    init(size)
  }

  override fun expandId(id32: Int): Long {
    var id32 = id32
    var dlon = 0
    var dlat = 0

    var bm = 1
    while (bm < 0x8000) {
      if ((id32 and 1) != 0) dlon = dlon or bm
      if ((id32 and 2) != 0) dlat = dlat or bm
      id32 = id32 shr 2
      bm = bm shl 1
    }

    val lon32 = lonBase + dlon
    val lat32 = latBase + dlat

    return (lon32.toLong() shl 32) or lat32.toLong()
  }

  override fun shrinkId(id64: Long): Int {
    val lon32 = (id64 shr 32).toInt()
    val lat32 = (id64 and 0xffffffffL).toInt()
    val dlon = lon32 - lonBase
    val dlat = lat32 - latBase
    var id32 = 0

    var bm = 0x4000
    while (bm > 0) {
      id32 = id32 shl 2
      if ((dlon and bm) != 0) id32 = id32 or 1
      if ((dlat and bm) != 0) id32 = id32 or 2
      bm = bm shr 1
    }
    return id32
  }

  override fun isInternal(ilon: Int, ilat: Int): Boolean {
    return ilon >= lonBase && ilon < lonBase + cellsize &&
      ilat >= latBase && ilat < latBase + cellsize
  }
}
