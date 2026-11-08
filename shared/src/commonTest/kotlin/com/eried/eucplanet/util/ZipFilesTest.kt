package com.eried.eucplanet.util

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The iPhone's trip zip, on the JVM and the iOS simulator. */
class ZipFilesTest {

    private val csv = "Date,Speed\n01.06.2026 20:24:31.204,5.00\n".repeat(20)

    @Test fun whatIsWrittenReadsBack() {
        val entries = listOf(
            ZipFiles.Entry("trip_a.csv", csv.encodeToByteArray()),
            ZipFiles.Entry("empty.csv", ByteArray(0)),
            ZipFiles.Entry("Ærø ride.csv", "Date\n".encodeToByteArray()),
        )
        val zip = ZipFiles.write(entries)
        // Local file header, deflated.
        assertEquals(0x50, zip[0].toInt())
        assertEquals(0x4B, zip[1].toInt())
        val back = ZipFiles.read(zip)
        assertEquals(entries.map { it.name }, back.map { it.name })
        entries.zip(back).forEach { (a, b) -> assertContentEquals(a.bytes, b.bytes, a.name) }
        assertEquals(3, ZipFiles.tripCsvs(zip).size)
    }

    @Test fun aZipFromAnotherToolOpens() {
        // Written by Python's zipfile to a stream it could not seek: data
        // descriptors after each entry, a folder, a stored entry, macOS's
        // shadow folder and a file that is not a trip.
        val zip = Base64.decode(FIXTURE)
        val all = ZipFiles.read(zip)
        assertEquals(listOf("trips/", "trips/trip_a.csv", "B.CSV", "__MACOSX/._trip_a.csv", "notes.txt"), all.map { it.name })
        val trips = ZipFiles.tripCsvs(zip)
        assertEquals(listOf("trips/trip_a.csv", "B.CSV"), trips.map { it.name })
        assertEquals(csv, trips[0].bytes.decodeToString())
        assertEquals("Date,Speed\n", trips[1].bytes.decodeToString())
    }

    private companion object {
        const val FIXTURE =
            "UEsDBBQACAAAAAAAIQAAAAAAAAAAAAAAAAAGAAAAdHJpcHMvUEsHCAAAAAAAAAAAAAAAAFBLAwQUAAgACAAAACEAAAAAAAAAAAAAAAAAEAAAAHRyaXBzL3RyaXBfYS5jc3ZzSSxJ1QkuSE1N4TIw1DMw0zMyMDJTMDKwMjKxMjYE8kx0TPUMDLhcRtWNqhuB6gBQSwcIl6yRBzAAAAAgAwAAUEsDBBQACAAAAAAAIQAAAAAAAAAAAAAAAAAFAAAAQi5DU1ZEYXRlLFNwZWVkClBLBwjwCALeCwAAAAsAAABQSwMEFAAIAAgAAAAhAAAAAAAAAAAAAAAAABUAAABfX01BQ09TWC8uX3RyaXBfYS5jc3bLKs3LBgBQSwcIOZz7BgYAAAAEAAAAUEsDBBQACAAIAAAAIQAAAAAAAAAAAAAAAAAJAAAAbm90ZXMudHh0y0jNyckHAFBLBwiGphA2BwAAAAUAAABQSwECFAMUAAgAAAAAACEAAAAAAAAAAAAAAAAABgAAAAAAAAAAAAAAgAEAAAAAdHJpcHMvUEsBAhQDFAAIAAgAAAAhAJeskQcwAAAAIAMAABAAAAAAAAAAAAAAAIABNAAAAHRyaXBzL3RyaXBfYS5jc3ZQSwECFAMUAAgAAAAAACEA8AgC3gsAAAALAAAABQAAAAAAAAAAAAAAgAGiAAAAQi5DU1ZQSwECFAMUAAgACAAAACEAOZz7BgYAAAAEAAAAFQAAAAAAAAAAAAAAgAHgAAAAX19NQUNPU1gvLl90cmlwX2EuY3N2UEsBAhQDFAAIAAgAAAAhAIamEDYHAAAABQAAAAkAAAAAAAAAAAAAAIABKQEAAG5vdGVzLnR4dFBLBQYAAAAABQAFAB8BAABnAQAAAAA="
    }
}
