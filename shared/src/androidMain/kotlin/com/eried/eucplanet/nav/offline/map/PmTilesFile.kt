package com.eried.eucplanet.nav.offline.map

import java.io.File
import java.io.RandomAccessFile

/** A [RandomReadFile] over a `RandomAccessFile`, the reader PmTiles always used on Android. */
class PmTilesFile(file: File) : RandomReadFile {
    private val raf = RandomAccessFile(file, "r")

    override fun length(): Long = raf.length()

    override fun readFully(at: Long, out: ByteArray) {
        raf.seek(at)
        raf.readFully(out)
    }

    override fun close() = raf.close()
}

/** The square in [file], opened as PmTiles always opened it. */
fun PmTiles.Companion.open(file: File): PmTiles = PmTiles(PmTilesFile(file))
