package com.eried.eucplanet.ble

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The write type line both phones put in a Service Mode log, and the V1
 * split the iPhone's writer now uses too (it ran on Android only).
 */
class BleWriteNoteTest {

    @Test fun theNoteReadsAsAndroidWroteIt() {
        assertEquals(
            "Write characteristic props=0x0c (write write-no-response), using write-with-response",
            BleProfile.writeTypeNote(0x0c, GattCharacteristic.WRITE_TYPE_DEFAULT, switched = false),
        )
        assertEquals(
            "Write characteristic props=0x04 (write-no-response), using write-no-response (switched from the family default)",
            BleProfile.writeTypeNote(0x04, GattCharacteristic.WRITE_TYPE_NO_RESPONSE, switched = true),
        )
        assertEquals(
            "Write characteristic props=0x00 (none), using write-with-response",
            BleProfile.writeTypeNote(0, GattCharacteristic.WRITE_TYPE_DEFAULT, switched = false),
        )
    }

    @Test fun anInMotionV1FrameGoesOutInTwentyBytePieces() {
        val frame = ByteArray(45) { it.toByte() }
        val pieces = BleProfile.INMOTION_V1.writePieces(frame)
        assertEquals(listOf(20, 20, 5), pieces.map { it.size })
        assertEquals(frame.toList(), pieces.flatMap { it.toList() })
        assertEquals(1, BleProfile.HM10.writePieces(ByteArray(20)).size)
    }
}
