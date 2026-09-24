package com.eried.eucplanet.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KingSong lock, from the tester's KS-18XL (FW 2.00) capture of the official
 * app, issue #19, 2026-09-16 16:51 UTC:
 *
 *   TX aa 55 01 00 .. 5d 14 5a 5a          lock
 *   RX aa 55 01 00 .. 5f 14 5a 5a          state: locked (wheel pings when moved)
 *   TX aa 55 00 .. 5e 14 5a 5a             ask
 *   RX aa 55 01 .. 5f                      still locked
 *   TX aa 55 00×8 35 30 39 35 34 30 5d ..  unlock with six digits, "509540" here
 *   RX aa 55 00 .. 5f                      state: unlocked
 *
 * A second capture unlocked with "763021" while the wheel reported "123456" as
 * its stored code, so with no rider-set code the digits are not checked. The
 * app sends the code from Advanced settings, 123456 unless the rider set one.
 * Nothing in the public protocol described any of this, which is why the
 * adapter used to say KingSong has no lock.
 */
class KingsongLockTest {

    private fun String.hex(): ByteArray =
        trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()

    private fun ks(type: Int, payload: ByteArray = ByteArray(14)): ByteArray {
        val f = ByteArray(20)
        f[0] = 0xAA.toByte(); f[1] = 0x55
        payload.copyInto(f, 2)
        f[16] = type.toByte(); f[17] = 0x14; f[18] = 0x5A; f[19] = 0x5A
        return f
    }

    @Test fun `the lock frame is the capture, byte for byte`() {
        assertArrayEquals(
            "aa 55 01 00 00 00 00 00 00 00 00 00 00 00 00 00 5d 14 5a 5a".hex(),
            KingsongCommands.lock(),
        )
    }

    @Test fun `the unlock frame carries the six digits as ASCII at offsets 10 to 15`() {
        assertArrayEquals(
            "aa 55 00 00 00 00 00 00 00 00 35 30 39 35 34 30 5d 14 5a 5a".hex(),
            KingsongCommands.unlock("509540"),
        )
        assertArrayEquals(
            "aa 55 00 00 00 00 00 00 00 00 00 00 00 00 00 00 5e 14 5a 5a".hex(),
            KingsongCommands.queryLock(),
        )
    }

    @Test fun `anything but six digits sends the wheel default instead of nothing`() {
        for (bad in listOf("", "12345", "1234567", "50954a", "509 40")) {
            assertArrayEquals("\"$bad\" falls back to 123456",
                KingsongCommands.unlock(KingsongCommands.DEFAULT_LOCK_CODE), KingsongCommands.unlock(bad))
        }
        assertArrayEquals(
            "aa 55 00 00 00 00 00 00 00 00 31 32 33 34 35 36 5d 14 5a 5a".hex(),
            KingsongCommands.unlock("123456"),
        )
    }

    @Test fun `the adapter locks without a code and unlocks with the one it was given`() {
        val a = KingsongAdapter()
        assertTrue(a.capabilities.hasLock)
        assertFalse(a.capabilities.needsAuthForLock)
        assertArrayEquals(KingsongCommands.lock(), a.setLock(true))
        assertArrayEquals("the follow-up reads the state back", KingsongCommands.queryLock(), a.setLockFollowup(true))

        assertArrayEquals("no code handed over yet: the wheel default goes out",
            KingsongCommands.unlock(KingsongCommands.DEFAULT_LOCK_CODE), a.setLock(false))
        a.provideLockCode("509540")
        assertArrayEquals(KingsongCommands.unlock("509540"), a.setLock(false))
    }

    @Test fun `the password frame is the capture, and it goes out before every lock action`() {
        assertArrayEquals(
            "aa 55 39 31 31 31 00 00 00 00 00 00 00 00 00 00 41 14 5a 5a".hex(),
            KingsongCommands.password("9111"),
        )
        for (bad in listOf("", "91a1", "9111111", "9 11")) {
            assertNull("\"$bad\" is not a password", KingsongCommands.password(bad))
        }

        val a = KingsongAdapter()
        assertNull("no password, no prelude, the init sequence stays as it was", a.lockPrelude())
        assertEquals(2, a.initSequence().size)
        a.provideLockPassword("9111")
        assertArrayEquals(KingsongCommands.password("9111"), a.lockPrelude())
        assertArrayEquals("sent on connect too", KingsongCommands.password("9111"), a.initSequence().last())
    }

    @Test fun `the 0x5F frame reports the lock state and later frames keep it`() {
        val a = KingsongAdapter()
        val locked = a.onRawNotification(ks(0x5F, byteArrayOf(0x01)))
        val t = locked.filterIsInstance<DecodeResult.Telemetry>().single()
        assertEquals(true, t.data.lockedReported)

        // A realtime frame from the capture (speed 0, the pack at 82 V) must not
        // drop the state: the icon is only as good as the last frame it saw.
        val a9 = ks(0xA9, "1c 20 00 00 04 00 1d 45 08 00 1c 0c 00 e0".hex())
        val live = a.onRawNotification(a9).filterIsInstance<DecodeResult.Telemetry>()
        assertTrue(live.isNotEmpty())
        assertEquals(true, live.last().data.lockedReported)

        val unlocked = a.onRawNotification(ks(0x5F, byteArrayOf(0x00)))
        assertEquals(false, unlocked.filterIsInstance<DecodeResult.Telemetry>().single().data.lockedReported)

        a.onDisconnect()
        val fresh = a.onRawNotification(a9).filterIsInstance<DecodeResult.Telemetry>()
        assertNull("a new session knows nothing until the wheel says", fresh.last().data.lockedReported)
    }
}
