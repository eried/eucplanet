package com.eried.eucplanet.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** An iPhone export opens in Android's importer, and an Android export on the iPhone. */
class ZipFilesJvmTest {

    private val trips = mapOf(
        "trip_20260601_202431.csv" to "Date,Speed\n01.06.2026 20:24:31.204,5.00\n".repeat(200),
        "trip_20260602_080000.csv" to "Date,Speed\n",
    )

    @Test fun anIphoneZipReadsInAndroidsImporter() {
        val zip = ZipFiles.write(trips.map { ZipFiles.Entry(it.key, it.value.encodeToByteArray()) })
        val read = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                read[e.name] = zis.readBytes().decodeToString()
                e = zis.nextEntry
            }
        }
        assertEquals(trips, read)
    }

    @Test fun anAndroidZipReadsOnTheIphone() {
        // Exactly what RecordingPortsAndroid.exportAllAsZip writes.
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, text) in trips) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.encodeToByteArray())
                zos.closeEntry()
            }
        }
        val read = ZipFiles.tripCsvs(bos.toByteArray())
        assertEquals(trips.keys.toList(), read.map { it.name })
        read.forEach { assertArrayEquals(trips.getValue(it.name).encodeToByteArray(), it.bytes) }
    }
}
