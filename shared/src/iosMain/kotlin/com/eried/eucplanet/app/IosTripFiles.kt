package com.eried.eucplanet.app

import com.eried.eucplanet.data.repository.LineSink
import com.eried.eucplanet.data.repository.LineSource
import com.eried.eucplanet.data.repository.TripToolFiles
import com.eried.eucplanet.data.repository.readerLines
import com.eried.eucplanet.data.sync.TripFile
import com.eried.eucplanet.data.sync.TripFileStore
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import kotlin.math.roundToLong

/**
 * The iPhone's trips folder (Documents/trips) for the shared Dropbox sync:
 * the same CSVs the recorder writes, with the file dates the sync compares
 * against Dropbox's.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosTripFiles(private val dir: () -> String) : TripFileStore {
    private val fm get() = NSFileManager.defaultManager

    private fun folder(): String = dir().also { fm.createDirectoryAtPath(it, true, null, null) }

    override fun files(): List<TripFile> {
        val d = folder()
        return (fm.contentsOfDirectoryAtPath(d, null) ?: emptyList<Any?>())
            .filterIsInstance<String>()
            .sorted()
            .map { IosTripFile("$d/$it", it) }
            .filter { it.isRegularFile() }
    }

    override fun file(name: String): TripFile = IosTripFile("${folder()}/$name", name)

    override fun writeWhole(name: String, bytes: ByteArray): TripFile {
        val path = "${folder()}/$name"
        val data = if (bytes.isEmpty()) NSData() else bytes.usePinned {
            NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong())
        }
        // Atomically: Foundation writes beside the file and renames, so it is
        // the whole trip or the old one, never half.
        if (!data.writeToFile(path, atomically = true)) error("Could not write $name")
        return IosTripFile(path, name)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class IosTripFile(private val path: String, override val name: String) : TripFile {
    private val fm get() = NSFileManager.defaultManager
    private fun attributes(): Map<Any?, *>? = fm.attributesOfItemAtPath(path, null)

    fun isRegularFile(): Boolean = attributes()?.get(NSFileType) == NSFileTypeRegular

    override fun exists() = fm.fileExistsAtPath(path)

    override fun length(): Long = (attributes()?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun lastModified(): Long =
        (attributes()?.get(NSFileModificationDate) as? NSDate)
            ?.let { (it.timeIntervalSince1970 * 1000.0).roundToLong() } ?: 0L

    override fun readBytes(): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: error("Could not read $name")
        val n = data.length.toInt()
        return if (n == 0) ByteArray(0) else data.bytes!!.readBytes(n)
    }

    override fun setLastModified(ms: Long): Boolean = fm.setAttributes(
        mapOf<Any?, Any?>(NSFileModificationDate to NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)),
        ofItemAtPath = path, error = null,
    )

    override fun delete(): Boolean = fm.removeItemAtPath(path, null)

    override fun <R> useLines(block: (Sequence<String>) -> R): R {
        val text = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)
            ?: error("Could not read $name")
        return block(text.lineSequence())
    }
}

/**
 * Documents/trips for the shared trip tools (split, combine, rename, change
 * wheel). A file is read whole, as the trip viewer reads it, and a rewrite is
 * written in one go when its last line is in.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosTripToolFiles(private val dir: () -> String) : TripToolFiles {
    private val fm get() = NSFileManager.defaultManager
    private fun path(name: String) = "${dir()}/$name"

    override fun exists(name: String) = fm.fileExistsAtPath(path(name))

    override fun length(name: String): Long =
        (fm.attributesOfItemAtPath(path(name), null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    override fun delete(name: String): Boolean = fm.removeItemAtPath(path(name), null)

    override fun rename(from: String, to: String): Boolean = fm.moveItemAtPath(path(from), path(to), null)

    override fun lines(name: String): LineSource = object : LineSource {
        override fun <R> useLines(block: (Sequence<String>) -> R): R {
            val text = NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)
                ?: error("Could not read $name")
            return block(text.readerLines())
        }
    }

    override fun <R> write(name: String, block: (LineSink) -> R): R {
        val out = StringBuilder()
        var any = false
        val result = block(LineSink { line ->
            any = true
            out.append(line).append('\n')
        })
        if (any) {
            fm.createDirectoryAtPath(dir(), true, null, null)
            val bytes = out.toString().encodeToByteArray()
            val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
            if (!data.writeToFile(path(name), atomically = true)) error("Could not write $name")
        }
        return result
    }
}
