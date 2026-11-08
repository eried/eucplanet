package com.eried.eucplanet.util

import com.eried.eucplanet.platform.ByteArrayOutputStream
import com.eried.eucplanet.platform.crc32
import com.eried.eucplanet.platform.gzip
import com.eried.eucplanet.platform.inflateRaw

/**
 * A plain .zip of named files, written and read without java.util.zip, for
 * the iPhone's trip export and import. Android keeps ZipOutputStream and
 * ZipInputStream; the files are the same format both ways (stored or
 * deflated entries, CRC-32, a central directory), so a zip made on one phone
 * opens on the other, on a desktop and in the trip viewer.
 */
object ZipFiles {

    class Entry(val name: String, val bytes: ByteArray)

    /**
     * The CSVs a trip import takes out of [zip]: files ending in .csv, not
     * folders and not macOS's __MACOSX shadow copies, as Android's importer.
     */
    fun tripCsvs(zip: ByteArray): List<Entry> = read(zip).filter {
        val n = it.name.lowercase()
        !n.endsWith("/") && n.endsWith(".csv") && !n.startsWith("__macosx")
    }

    /** Deflated entries, stamped with [dosTime]/[dosDate] (MS-DOS format, local time). */
    fun write(entries: List<Entry>, dosTime: Int = 0, dosDate: Int = (1 shl 5) or 1): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0L
        for (e in entries) {
            val name = e.name.encodeToByteArray()
            val crc = crc32(e.bytes)
            // gzip is a 10-byte header, raw DEFLATE, then an 8-byte trailer.
            val gz = gzip(e.bytes)
            val deflated = gz.copyOfRange(10, gz.size - 8)
            val flags = 0x0800 // names are UTF-8
            val local = ByteArrayOutputStream()
            local.le32(0x04034b50); local.le16(20); local.le16(flags); local.le16(8)
            local.le16(dosTime); local.le16(dosDate)
            local.le32(crc); local.le32(deflated.size.toLong()); local.le32(e.bytes.size.toLong())
            local.le16(name.size); local.le16(0)
            local.write(name)
            val header = local.toByteArray()
            out.write(header)
            out.write(deflated)

            central.le32(0x02014b50); central.le16(20); central.le16(20); central.le16(flags); central.le16(8)
            central.le16(dosTime); central.le16(dosDate)
            central.le32(crc); central.le32(deflated.size.toLong()); central.le32(e.bytes.size.toLong())
            central.le16(name.size); central.le16(0); central.le16(0); central.le16(0); central.le16(0)
            central.le32(0); central.le32(offset)
            central.write(name)
            offset += header.size + deflated.size
        }
        val dir = central.toByteArray()
        out.write(dir)
        out.le32(0x06054b50); out.le16(0); out.le16(0)
        out.le16(entries.size); out.le16(entries.size)
        out.le32(dir.size.toLong()); out.le32(offset); out.le16(0)
        return out.toByteArray()
    }

    /**
     * Every file in [zip], through its central directory (so entries written
     * with a trailing data descriptor read too). Stored and deflated entries;
     * anything else, or a damaged archive, throws.
     */
    fun read(zip: ByteArray): List<Entry> {
        val end = (zip.size - 22 downTo maxOf(0, zip.size - 22 - 0xFFFF))
            .firstOrNull { zip.u32(it) == 0x06054b50L }
            ?: throw IllegalArgumentException("not a zip")
        val count = zip.u16(end + 10)
        var at = zip.u32(end + 16).toInt()
        val out = ArrayList<Entry>(count)
        repeat(count) {
            require(zip.u32(at) == 0x02014b50L) { "bad central directory" }
            val method = zip.u16(at + 10)
            val compressed = zip.u32(at + 20).toInt()
            val size = zip.u32(at + 24).toInt()
            val nameLen = zip.u16(at + 28)
            val extraLen = zip.u16(at + 30)
            val commentLen = zip.u16(at + 32)
            val localAt = zip.u32(at + 42).toInt()
            val name = zip.copyOfRange(at + 46, at + 46 + nameLen).decodeToString()
            at += 46 + nameLen + extraLen + commentLen

            require(zip.u32(localAt) == 0x04034b50L) { "bad local header for $name" }
            val dataAt = localAt + 30 + zip.u16(localAt + 26) + zip.u16(localAt + 28)
            val raw = zip.copyOfRange(dataAt, dataAt + compressed)
            val bytes = when (method) {
                0 -> raw
                8 -> if (size == 0) ByteArray(0) else inflateRaw(raw)
                else -> throw IllegalArgumentException("$name: compression method $method")
            }
            out += Entry(name, bytes)
        }
        return out
    }

    private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(at: Int): Long = (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.le32(v: Long) { for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt()) }
    private fun ByteArrayOutputStream.le32(v: Int) = le32(v.toLong() and 0xFFFFFFFFL)
}
