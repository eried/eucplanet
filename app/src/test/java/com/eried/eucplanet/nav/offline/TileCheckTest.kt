package com.eried.eucplanet.nav.offline

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A downloaded square is kept only when BRouter can read all of it. */
class TileCheckTest {

    @Test fun anEmptyOrJunkFile_isRefused() {
        val empty = File.createTempFile("empty", ".rd5").apply { deleteOnExit() }
        assertNotNull(TileCheck.problem(empty))
        val junk = File.createTempFile("junk", ".rd5").apply {
            deleteOnExit()
            writeBytes(ByteArray(4096) { (it * 31).toByte() })
        }
        assertNotNull(TileCheck.problem(junk))
    }

    @Test fun aRealSquare_passes_andOneFlippedBitIsCaught() {
        // Runs only when BROUTER_TILES names a folder with E10_N55.rd5.
        val real = System.getenv("BROUTER_TILES")?.let { File(it, "E10_N55.rd5") }
        assumeTrue(real != null && real.isFile)
        assertNull(TileCheck.problem(real!!))

        val copy = File.createTempFile("E10_N55", ".rd5").apply { deleteOnExit() }
        real.copyTo(copy, overwrite = true)
        RandomAccessFile(copy, "rw").use { f ->
            f.seek(copy.length() / 2)
            val b = f.read()
            f.seek(copy.length() / 2)
            f.write(b xor 0x40)
        }
        assertNotNull(TileCheck.problem(copy))
    }
}
