package com.eried.eucplanet.ble.virtual

import com.eried.eucplanet.ble.DecodeResult
import com.eried.eucplanet.ble.KingsongAdapter
import com.eried.eucplanet.ble.KingsongCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The virtual KS-18XL answers the lock handshake the way the real one did in
 * the issue #19 capture, and its frames go through the real KingSong adapter
 * unchanged, so an emulator session exercises the same parser a rider's
 * phone would.
 */
class KingsongVirtualWheelTest {

    @Before fun freshWheel() = KingsongVirtualWheel.factoryReset()

    private fun lockedReported(frames: List<ByteArray>, adapter: KingsongAdapter): Boolean? =
        frames.flatMap { adapter.onRawNotification(it) }
            .filterIsInstance<DecodeResult.Telemetry>()
            .lastOrNull()?.data?.lockedReported

    @Test fun `a lock survives a reconnect, and the connect sequence reads it back`() {
        val first = KingsongVirtualWheel()
        val a = KingsongAdapter()
        a.provideLockPassword(first.password)
        a.notifyConnectingTo(first.bleName)
        first.onWrite(KingsongCommands.password(first.password)!!)
        first.onWrite(KingsongCommands.lock())
        assertTrue(first.locked)

        // Phil's report: lock, power off, come back later and reconnect.
        a.onDisconnect()
        val again = KingsongVirtualWheel()
        assertTrue("the wheel keeps its lock through power-off", again.locked)
        a.notifyConnectingTo(again.bleName)
        val replies = a.initSequence().flatMap { again.onWrite(it) }
        assertEquals("connecting alone must learn the wheel is locked",
            true, lockedReported(replies, a))
    }

    @Test fun `an acknowledged settings push gets no reply, a bare query does`() {
        val w = KingsongVirtualWheel()
        assertEquals(1, w.onWrite(KingsongCommands.queryLimits()).size)
        val ack = KingsongCommands.queryLimits().copyOf().also { it[2] = 0x01; it[4] = 0x0f }
        assertTrue(w.onWrite(ack).isEmpty())
    }

    @Test fun `it is registered and names itself as a KingSong`() {
        val w = VirtualWheelRegistry.create("KS18XL")
        assertNotNull(w)
        assertTrue(w is KingsongVirtualWheel)
        assertEquals("KS-18XL", w!!.bleName)
    }

    @Test fun `with the password set, lock is ignored until the password has been sent`() {
        val w = KingsongVirtualWheel()
        val a = KingsongAdapter()
        a.notifyConnectingTo(w.bleName)

        assertEquals("the tester's report: lock has no effect",
            false, lockedReported(w.onWrite(KingsongCommands.lock()), a))
        assertFalse(w.locked)

        assertTrue(w.onWrite(KingsongCommands.password("0000")!!).isEmpty())
        assertFalse(w.passwordAccepted)
        val answer = w.onWrite(KingsongCommands.password(w.password)!!)
        assertEquals(1, answer.size)
        assertEquals(0x43, answer[0][16].toInt())
        assertTrue(w.passwordAccepted)
        // The adapter reads the answer as a note, never as telemetry.
        a.provideLockPassword(w.password)
        assertTrue(a.onRawNotification(answer[0]).isEmpty())

        assertEquals(true, lockedReported(w.onWrite(KingsongCommands.lock()), a))
        assertTrue(w.locked)
    }

    @Test fun `lock, then any six digits unlock it, as the tester's wheel did`() {
        val w = KingsongVirtualWheel()
        val a = KingsongAdapter()
        a.notifyConnectingTo(w.bleName)
        w.onWrite(KingsongCommands.password(w.password)!!)

        assertEquals(false, lockedReported(w.onWrite(KingsongCommands.queryLock()), a))

        assertEquals(true, lockedReported(w.onWrite(KingsongCommands.lock()), a))
        assertTrue(w.locked)

        assertEquals("digits the wheel never saw still unlock it, no code was set",
            false, lockedReported(w.onWrite(KingsongCommands.unlock("509540")), a))
        assertFalse(w.locked)

        assertEquals(true, lockedReported(w.onWrite(KingsongCommands.lock()), a))
        assertEquals(false, lockedReported(w.onWrite(KingsongCommands.unlock(w.lockCode)), a))
        assertFalse(w.locked)
    }

    @Test fun `its frames parse as an 82 V KingSong that stands still while locked`() {
        val w = KingsongVirtualWheel()
        val a = KingsongAdapter()
        a.notifyConnectingTo(w.bleName)
        w.onConnect().forEach { a.onRawNotification(it) }
        w.onWrite(KingsongCommands.password(w.password)!!)

        w.onWrite(KingsongCommands.lock())
        val locked = w.onTick(3_000L).flatMap { a.onRawNotification(it) }
            .filterIsInstance<DecodeResult.Telemetry>().last().data
        assertEquals(82.18f, locked.voltage, 0.01f)
        assertEquals(0f, locked.speed, 0.001f)

        w.onWrite(KingsongCommands.unlock(w.lockCode))
        val rolling = w.onTick(3_000L).flatMap { a.onRawNotification(it) }
            .filterIsInstance<DecodeResult.Telemetry>().last().data
        assertTrue("unlocked, the sine rolls again: ${rolling.speed}", rolling.speed > 1f)
    }
}
