package btools.util

import java.io.File
import java.io.RandomAccessFile

actual typealias IOException = java.io.IOException

actual object BFiles {
    actual fun isFile(path: String): Boolean = File(path).isFile

    actual fun length(path: String): Long = File(path).length()

    actual fun lastModified(path: String): Long = File(path).lastModified()

    actual fun readBytes(path: String): ByteArray = File(path).readBytes()

    actual fun openRandom(path: String): RandomReadFile = JvmRandomReadFile(RandomAccessFile(path, "r"))
}

private class JvmRandomReadFile(private val raf: RandomAccessFile) : RandomReadFile {
    override fun length(): Long = raf.length()

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        raf.seek(pos)
        raf.readFully(buf, off, len)
    }

    override fun close() = raf.close()
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()
