package btools.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.gettimeofday
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror
import platform.posix.timeval
import kotlinx.cinterop.toKString

actual open class IOException actual constructor(message: String?) : Exception(message)

@OptIn(ExperimentalForeignApi::class)
actual object BFiles {
    private class Info(val regular: Boolean, val size: Long, val mtimeMs: Long)

    private fun info(path: String): Info? = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return null
        Info(
            (st.st_mode.toInt() and S_IFMT.toInt()) == S_IFREG.toInt(),
            st.st_size,
            st.st_mtimespec.tv_sec * 1000L + st.st_mtimespec.tv_nsec / 1_000_000L,
        )
    }

    actual fun isFile(path: String): Boolean = info(path)?.regular == true

    actual fun length(path: String): Long = info(path)?.size ?: 0L

    actual fun lastModified(path: String): Long = info(path)?.mtimeMs ?: 0L

    actual fun readBytes(path: String): ByteArray {
        val f = openRandom(path)
        try {
            val n = f.length()
            if (n > Int.MAX_VALUE) throw IOException("$path: too big")
            val out = ByteArray(n.toInt())
            if (out.isNotEmpty()) f.readFully(0, out, 0, out.size)
            return out
        } finally {
            f.close()
        }
    }

    actual fun openRandom(path: String): RandomReadFile {
        val fd = open(path, O_RDONLY)
        if (fd < 0) throw IOException("$path: ${strerror(errno)?.toKString()}")
        return PosixRandomReadFile(fd, path)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixRandomReadFile(private var fd: Int, private val path: String) : RandomReadFile {
    private val size: Long = memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) throw IOException("$path: ${strerror(errno)?.toKString()}")
        st.st_size
    }

    override fun length(): Long = size

    override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > buf.size) throw IndexOutOfBoundsException()
        if (len == 0) return
        var done = 0
        buf.usePinned { pinned ->
            while (done < len) {
                val n = pread(fd, pinned.addressOf(off + done), (len - done).toULong(), pos + done)
                if (n < 0) throw IOException("$path: ${strerror(errno)?.toKString()}")
                if (n == 0L) throw IOException("$path: end of file")
                done += n.toInt()
            }
        }
    }

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long = memScoped {
    val tv = alloc<timeval>()
    gettimeofday(tv.ptr, null)
    tv.tv_sec * 1000L + tv.tv_usec / 1000L
}
