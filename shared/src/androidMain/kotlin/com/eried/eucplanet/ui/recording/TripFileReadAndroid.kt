package com.eried.eucplanet.ui.recording

import java.io.File
import java.io.RandomAccessFile

/** [this] trip file opened for [TripTail]; null when it is not there. */
fun File.openTripFile(): TripFileRead? {
    if (!exists()) return null
    val raf = RandomAccessFile(this, "r")
    return object : TripFileRead {
        override val length: Long = raf.length()
        override fun read(at: Long, count: Int): ByteArray {
            val bytes = ByteArray(count)
            raf.seek(at)
            raf.readFully(bytes)
            return bytes
        }
        override fun close() = raf.close()
    }
}

/** A first, whole read of [file]. */
fun TripTail.Companion.read(file: File): TripTail = read { file.openTripFile() }

/** [file] read further, see [TripTail.advance]. */
fun TripTail.advance(file: File): TripTail = advance { file.openTripFile() }

/** Every row of [file]. Empty when it is missing or has no header. */
fun TripCsvPoints.Companion.readAll(file: File): List<TripDataPoint> {
    if (!file.exists()) return emptyList()
    return file.bufferedReader().useLines { readAll(it) }
}
