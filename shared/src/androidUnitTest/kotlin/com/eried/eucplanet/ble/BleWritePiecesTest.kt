package com.eried.eucplanet.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a frame is cut into the writes that go on the air.
 *
 * InMotion V1 frames are all longer than the 20 bytes one write carries at the
 * MTU that family stays on, and Android sends anything longer as a GATT long
 * write. These pin that every V1 frame is cut into plain writes of 20 bytes or
 * less, in order, with nothing lost or reordered, and that no other family is
 * cut at all.
 */
class BleWritePiecesTest {

    private val v1 = BleProfile.INMOTION_V1

    private fun frame(size: Int) = ByteArray(size) { it.toByte() }

    private fun joined(pieces: List<ByteArray>): ByteArray =
        pieces.fold(ByteArray(0)) { acc, p -> acc + p }

    @Test fun `a 22-byte frame is a 20-byte write then a 2-byte write`() {
        val pieces = v1.writePieces(frame(22))
        assertEquals(listOf(20, 2), pieces.map { it.size })
        assertArrayEquals(frame(22), joined(pieces))
    }

    @Test fun `a 40-byte frame is two full writes`() {
        val pieces = v1.writePieces(frame(40))
        assertEquals(listOf(20, 20), pieces.map { it.size })
        assertArrayEquals(frame(40), joined(pieces))
    }

    @Test fun `a frame of exactly 20 bytes goes in one write, untouched`() {
        val data = frame(20)
        val pieces = v1.writePieces(data)
        assertEquals(1, pieces.size)
        assertSame(data, pieces[0])
    }

    @Test fun `a 21-byte frame leaves a 1-byte tail`() {
        val pieces = v1.writePieces(frame(21))
        assertEquals(listOf(20, 1), pieces.map { it.size })
        assertArrayEquals(frame(21), joined(pieces))
    }

    @Test fun `pieces keep the frame order`() {
        val pieces = v1.writePieces(frame(45))
        assertEquals(listOf(20, 20, 5), pieces.map { it.size })
        // Each piece starts where the previous one stopped.
        assertEquals(0, pieces[0][0].toInt())
        assertEquals(20, pieces[1][0].toInt())
        assertEquals(40, pieces[2][0].toInt())
    }

    @Test fun `every real V1 frame is cut, escapes and all, and rejoins exactly`() {
        val frames = listOf(
            InMotionV1Commands.getFastInfo(),
            InMotionV1Commands.getSlowInfo(),
            InMotionV1Commands.sendFactoryPassword(),
            InMotionV1Commands.sendPin("000000"),
            InMotionV1Commands.setLight(true),
            InMotionV1Commands.setLock(true),
        )
        for (f in frames) {
            // The premise: no V1 frame fits one plain write.
            assertTrue("V1 frame of ${f.size} bytes", f.size > 20)
            val pieces = v1.writePieces(f)
            assertTrue(pieces.all { it.size <= 20 })
            assertArrayEquals(f, joined(pieces))
            // Byte stuffing travels with the bytes: the rejoined frame still
            // unwraps to the same CAN frame and checksum.
            assertArrayEquals(InMotionV1Protocol.unwrap(f), InMotionV1Protocol.unwrap(joined(pieces)))
        }
    }

    @Test fun `the escaped 0x55 of a 0x0F55 CAN id survives the cut`() {
        val f = InMotionV1Commands.getFastInfo()
        // CAN id 0x0F550113 is written LE, so its 0x55 is stuffed as A5 55
        // right after the AA AA header and the first two id bytes.
        assertEquals(0xA5.toByte(), f[4])
        assertEquals(0x55.toByte(), f[5])
        val first = v1.writePieces(f)[0]
        assertEquals(0xA5.toByte(), first[4])
        assertEquals(0x55.toByte(), first[5])
    }

    @Test fun `other families are never cut`() {
        val long = frame(40)
        for (p in listOf(BleProfile.NORDIC_UART, BleProfile.HM10)) {
            val pieces = p.writePieces(long)
            assertEquals(1, pieces.size)
            assertSame(long, pieces[0])
        }
    }
}
