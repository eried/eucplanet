package com.eried.eucplanet.cloud

import com.eried.eucplanet.util.Crc32
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies the pure-Kotlin SHA-256 + gzip against known vectors (the server
 *  validates `file_sha256` against the uploaded CSV, so SHA-256 must be exact). */
class CloudUtilTest {
    @Test fun sha256_abc() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))

    @Test fun sha256_empty() =
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))

    @Test fun sha256_multiblock() =
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )

    @Test fun gzip_structureAndTrailer() {
        val data = "hello,world\n1,2,3\n42,7,9\n".encodeToByteArray()
        val gz = gzip(data)
        // gzip magic + deflate method
        assertEquals(0x1f.toByte(), gz[0])
        assertEquals(0x8b.toByte(), gz[1])
        assertEquals(0x08.toByte(), gz[2])
        val n = gz.size
        fun le32(off: Int): Long =
            (gz[off].toLong() and 0xff) or ((gz[off + 1].toLong() and 0xff) shl 8) or
                ((gz[off + 2].toLong() and 0xff) shl 16) or ((gz[off + 3].toLong() and 0xff) shl 24)
        assertEquals(Crc32.compute(data), le32(n - 8)) // CRC-32 trailer
        assertEquals(data.size.toLong(), le32(n - 4))  // ISIZE trailer
    }

    @Test fun isoUtc_epoch() = assertEquals("1970-01-01T00:00:00Z", isoUtc(0L))

    @Test fun isoUtc_billionSeconds() = assertEquals("2001-09-09T01:46:40Z", isoUtc(1_000_000_000_000L))

    @Test fun meta_hasShaAndFields() {
        val csv = "a,b\n1,2\n".encodeToByteArray()
        val meta = MetaBuilder.build("store123", "uuid-1", 0L, 60_000L, 2, csv, wheelJson = "{\"brand\":\"KingSong\"}")
        val o = Json.parseToJsonElement(meta).jsonObject
        assertEquals("store123", o["store_id"]!!.jsonPrimitive.content)
        assertEquals(sha256Hex(csv), o["file_sha256"]!!.jsonPrimitive.content)
        assertEquals("eucplanet", o["source_app"]!!.jsonPrimitive.content)
        assertEquals(2, o["sample_count"]!!.jsonPrimitive.int)
        assertEquals("1970-01-01T00:00:00Z", o["start_utc"]!!.jsonPrimitive.content)
    }

    @Test fun canonical_sortsKeysCompact() =
        assertEquals("""{"a":2,"b":1}""", canonicalJson(Json.parseToJsonElement("""{"b":1,"a":2}""")))

    @Test fun stubAttestation_addsBoundHash() {
        val payload = """{"store_id":"s1","display_name":"Bob"}"""
        val out = Json.parseToJsonElement(withStubAttestation(payload)).jsonObject
        val att = out["attestation"]!!.jsonObject
        assertEquals("play_integrity", att["type"]!!.jsonPrimitive.content)
        assertEquals("", att["token"]!!.jsonPrimitive.content)
        // request_hash = sha256(canonical(payload without attestation))
        val expected = sha256Hex(canonicalJson(Json.parseToJsonElement(payload)).encodeToByteArray())
        assertEquals(expected, att["request_hash"]!!.jsonPrimitive.content)
    }

    @Test fun uuid4_shape() {
        val u = uuid4()
        assertEquals(36, u.length)
        assertEquals('4', u[14])
        assertEquals('-', u[8]); assertEquals('-', u[13]); assertEquals('-', u[18]); assertEquals('-', u[23])
    }
}
