package com.eried.eucplanet.ble

import com.eried.eucplanet.data.model.WheelData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InMotion V1 lock, pinned to a real V8S capture.
 *
 * A rider recorded the InMotion app locking and then unlocking a V8S over BLE,
 * with a written timeline. Everything asserted here is a byte that was actually
 * on the wire, so this test fails if the command or the flag offset drifts.
 *
 * The family shipped for a long time with `hasLock = false` and a `setLock`
 * that returned null, on the belief that V1 had no remote lock at all. It has
 * one; nobody had captured it.
 */
class InMotionV1LockTest {

    /** The 16 CAN bytes the InMotion app sent, minus framing. From the capture. */
    private val capturedLock = byteArrayOf(
        0x16, 0x01, 0x55, 0x0F,                          // CAN 0x0F550116, LE
        0xB2.toByte(), 0x00, 0x00, 0x00,                 // remote-control group
        0x03, 0x00, 0x00, 0x00,                          // sub-command: lock
        0x08, 0x05, 0x00, 0x00,                          // len, channel, format, type
    )
    private val capturedUnlock = capturedLock.copyOf().also { it[8] = 0x04 }

    @Test fun lockFrameMatchesTheCapturedCommand() {
        val unwrapped = InMotionV1Protocol.unwrap(InMotionV1Commands.setLock(true))
        assertArrayEquals(capturedLock, unwrapped)
    }

    @Test fun unlockFrameMatchesTheCapturedCommand() {
        val unwrapped = InMotionV1Protocol.unwrap(InMotionV1Commands.setLock(false))
        assertArrayEquals(capturedUnlock, unwrapped)
    }

    @Test fun checksumsMatchTheOnesTheWheelAccepted() {
        // The app on the wire sent 0x3D for lock and 0x3E for unlock. Ours are
        // computed independently by InMotionV1Protocol, so agreement means the
        // whole frame agrees, not just the bytes we chose.
        assertEquals(0x3D.toByte(), InMotionV1Protocol.checksum(capturedLock))
        assertEquals(0x3E.toByte(), InMotionV1Protocol.checksum(capturedUnlock))
    }

    @Test fun lockAndUnlockDifferOnlyInTheSubCommand() {
        val lock = InMotionV1Protocol.unwrap(InMotionV1Commands.setLock(true))!!
        val unlock = InMotionV1Protocol.unwrap(InMotionV1Commands.setLock(false))!!
        val differing = lock.indices.filter { lock[it] != unlock[it] }
        assertEquals(listOf(8), differing)
    }

    @Test fun theAdapterActuallyOffersLockNow() {
        // Both halves of the old behaviour: a null command and a capability that
        // said the family could not lock. Either one alone re-breaks it.
        assertTrue(WheelCapabilities.INMOTION_V1.hasLock)
        // The V14 challenge/response does not exist here; V1 authenticates once
        // at connect with its password, so demanding auth would deadlock lock.
        assertTrue(!WheelCapabilities.INMOTION_V1.needsAuthForLock)
    }

    // --- Lock state, read back from telemetry ---

    private fun fastInfoPayload(byte61: Int): ByteArray =
        ByteArray(149).also {
            it[60] = 0x23          // work mode, constant in the capture
            it[61] = byte61.toByte()
        }

    @Test fun lockFlagIsReadFromByte61NotTheWorkMode() {
        val locked = InMotionV1Parser.parseFastInfo(fastInfoPayload(0x02), null)
        val unlocked = InMotionV1Parser.parseFastInfo(fastInfoPayload(0x00), null)
        assertEquals(true, locked?.lockedReported)
        assertEquals(false, unlocked?.lockedReported)
        // Byte 60 read 0x23 whether the wheel was locked or not, which is why
        // every attempt to decode lock from the work-mode nibble failed.
        assertEquals(locked?.pcMode, unlocked?.pcMode)
    }

    @Test fun aShortPayloadReportsNothingRatherThanUnlocked() {
        // Null, never false: a frame too short to carry the flag must not be
        // read as the wheel saying it is unlocked.
        val short = ByteArray(56)
        assertNull(InMotionV1Parser.parseFastInfo(short, null)?.lockedReported)
    }

    @Test fun otherFamiliesReportNothing() {
        assertNull(WheelData().lockedReported)
    }
}
