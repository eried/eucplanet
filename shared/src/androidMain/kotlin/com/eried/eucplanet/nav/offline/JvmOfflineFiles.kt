package com.eried.eucplanet.nav.offline

import android.os.StatFs
import java.io.File
import java.io.FileOutputStream

/** [OfflineFiles] over java.io.File, the calls the tile store always made. */
open class JvmOfflineFiles : OfflineFiles {
    override fun isFile(path: String) = File(path).isFile
    override fun length(path: String) = File(path).length()
    override fun lastModified(path: String) = File(path).lastModified()
    override fun setLastModified(path: String, ms: Long) = File(path).setLastModified(ms)
    override fun list(dir: String): List<String> =
        (File(dir).listFiles() ?: emptyArray()).filter { it.isFile }.map { it.name }
    override fun delete(path: String) = File(path).delete()
    override fun rename(from: String, to: String) = File(from).renameTo(File(to))
    override fun mkdirs(dir: String) { File(dir).mkdirs() }
    override fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()
    override fun writeText(path: String, text: String) = File(path).writeText(text)
    override fun writeBytes(path: String, bytes: ByteArray) = File(path).writeBytes(bytes)
    override fun copy(from: String, to: String) { File(from).copyTo(File(to), overwrite = true) }

    override fun openWrite(path: String, append: Boolean): FileSink {
        val out = FileOutputStream(File(path), append)
        return object : FileSink {
            override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)
            override fun close() = out.close()
        }
    }

    override fun freeBytes(dir: String): Long = StatFs(dir).availableBytes
}
