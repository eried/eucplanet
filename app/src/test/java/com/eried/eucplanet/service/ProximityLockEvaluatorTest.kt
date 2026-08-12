package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ProximityLockSettings
import com.eried.eucplanet.service.ProximityLockEvaluator.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The proximity lock is hard to test on a wheel: it needs a real rider walking
 * a real distance, and getting it wrong means the wheel unlocks in a bike rack.
 * These cover the awkward cases on the ground instead.
 */
class ProximityLockEvaluatorTest {

    private val both = ProximityLockSettings(
        lockEnabled = true,
        lockBelowDbm = -68,
        unlockEnabled = true,
        unlockAboveDbm = -62,
    )

    private val hold = ProximityLockEvaluator.HOLD_MS

    /** Feed one reading repeatedly until the hold expires; return every action. */
    private fun ProximityLockEvaluator.feed(
        rssi: Int,
        locked: Boolean,
        settings: ProximityLockSettings = both,
        startMs: Long = 0L,
        forMs: Long = hold,
        stepMs: Long = 250L,
    ): List<Action> = buildList {
        var t = startMs
        while (t <= startMs + forMs) {
            add(evaluate(t, rssi, locked, settings))
            t += stepMs
        }
    }

    @Test
    fun `locking by hand next to the wheel is not undone by auto-unlock`() {
        // The 0.16.0 report: rider presses Lock while standing beside the wheel,
        // so the signal is strong and the wheel is locked. Nothing armed the
        // unlock, so the automation must keep its hands off.
        val e = ProximityLockEvaluator()
        val actions = e.feed(rssi = -55, locked = true, forMs = hold * 4)
        assertEquals(emptyList<Action>(), actions.filter { it != Action.NONE })
    }

    @Test
    fun `walking away locks after the hold, not before`() {
        val e = ProximityLockEvaluator()
        assertEquals(Action.NONE, e.evaluate(0, -75, false, both))
        assertEquals(Action.NONE, e.evaluate(hold - 1, -75, false, both))
        assertEquals(Action.LOCK, e.evaluate(hold, -75, false, both))
    }

    @Test
    fun `a blip back into range restarts the hold`() {
        val e = ProximityLockEvaluator()
        e.evaluate(0, -75, false, both)
        e.evaluate(1000, -50, false, both)   // brief strong reading resets it
        assertEquals(Action.NONE, e.evaluate(1000 + hold, -75, false, both))
        assertEquals(Action.LOCK, e.evaluate(1000 + hold * 2, -75, false, both))
    }

    @Test
    fun `walk away then come back locks then unlocks`() {
        val e = ProximityLockEvaluator()
        assertEquals(Action.LOCK, e.feed(-75, locked = false).last())
        // Wheel is locked now. Rider returns.
        val back = e.feed(-55, locked = true, startMs = hold * 2)
        assertEquals(Action.UNLOCK, back.last())
    }

    @Test
    fun `unlock stays disarmed after it fires until the rider leaves again`() {
        val e = ProximityLockEvaluator()
        e.feed(-75, locked = false)                                  // auto-lock, arms
        e.feed(-55, locked = true, startMs = hold * 2)               // auto-unlock, disarms
        // Rider locks by hand again, still standing there: no second unlock.
        val after = e.feed(-55, locked = true, startMs = hold * 6, forMs = hold * 4)
        assertEquals(emptyList<Action>(), after.filter { it != Action.NONE })
    }

    @Test
    fun `each switch is obeyed on its own`() {
        val lockOnly = both.copy(unlockEnabled = false)
        val e = ProximityLockEvaluator()
        assertEquals(Action.LOCK, e.feed(-75, locked = false, settings = lockOnly).last())
        val stay = e.feed(-55, locked = true, settings = lockOnly, startMs = hold * 2)
        assertEquals(emptyList<Action>(), stay.filter { it != Action.NONE })

        // Unlock alone does nothing: it is a sub-option of the lock, not a
        // feature of its own. See the next test for why this matters.
        val unlockOnly = both.copy(lockEnabled = false)
        val u = ProximityLockEvaluator()
        val noLock = u.feed(-75, locked = false, settings = unlockOnly)
        assertEquals(emptyList<Action>(), noLock.filter { it != Action.NONE })
        u.feed(-75, locked = true, settings = unlockOnly)            // rider left, wheel locked
        val back = u.feed(-55, locked = true, settings = unlockOnly, startMs = hold * 2)
        assertEquals(emptyList<Action>(), back.filter { it != Action.NONE })
    }

    @Test
    fun `switching the feature off cannot leave the unlock half running`() {
        // Turning the proximity lock off clears lockEnabled but keeps
        // unlockEnabled, and the settings screen then hides the unlock switch,
        // so the rider can neither see it nor turn it off. It used to keep
        // firing: lock by hand, and the wheel unlocked itself a few seconds
        // later with the feature apparently switched off.
        val leftover = both.copy(lockEnabled = false, unlockEnabled = true)
        val e = ProximityLockEvaluator()
        e.feed(-75, locked = true, settings = leftover)               // would-be arming
        val actions = e.feed(-55, locked = true, settings = leftover, startMs = hold * 2, forMs = hold * 4)
        assertEquals(emptyList<Action>(), actions.filter { it != Action.NONE })
    }

    @Test
    fun `a dead-band is enforced even if the rider sets the two equal`() {
        // Both at -68 would let one reading satisfy lock and unlock at once.
        val equal = both.copy(lockBelowDbm = -68, unlockAboveDbm = -68)
        val e = ProximityLockEvaluator()
        e.feed(-68, locked = true, settings = equal)                 // arms: at the lock line
        // -68 must not unlock: the unlock line is pushed to -66.
        val at = e.feed(-68, locked = true, settings = equal, startMs = hold * 2, forMs = hold * 2)
        assertEquals(emptyList<Action>(), at.filter { it != Action.NONE })
        assertEquals(
            Action.UNLOCK,
            e.feed(-66, locked = true, settings = equal, startMs = hold * 6).last()
        )
    }

    @Test
    fun `no reading yet does nothing and does not break a hold in progress`() {
        val e = ProximityLockEvaluator()
        e.evaluate(0, -75, false, both)
        assertEquals(Action.NONE, e.evaluate(1000, 0, false, both))  // rssi 0 = unknown
        assertEquals(Action.LOCK, e.evaluate(hold, -75, false, both))
    }

    @Test
    fun `walking out of range and back still unlocks`() {
        // The feature's whole point, and the case a naive "clear everything on
        // disconnect" breaks: walking away far enough drops the link a few
        // steps after the auto-lock, so the arming has to outlive it.
        val e = ProximityLockEvaluator()
        assertEquals(Action.LOCK, e.feed(-75, locked = false).last())
        e.onLinkLost()                                               // walked out of range
        val back = e.feed(-55, locked = true, startMs = hold * 4)     // returned, reconnected
        assertEquals(Action.UNLOCK, back.last())
    }

    @Test
    fun `a link drop does not leave a half-finished hold running`() {
        val e = ProximityLockEvaluator()
        e.evaluate(0, -75, false, both)                              // lock hold starts
        e.onLinkLost()
        // The hold restarts from the reconnect, so a stale timestamp cannot
        // make the very first reading after reconnecting act immediately.
        assertEquals(Action.NONE, e.evaluate(hold, -75, false, both))
        assertEquals(Action.LOCK, e.evaluate(hold * 2, -75, false, both))
    }

    @Test
    fun `reset disarms so a reconnect cannot unlock a wheel left locked`() {
        val e = ProximityLockEvaluator()
        e.feed(-75, locked = false)                                  // auto-lock, arms
        e.reset()                                                    // Stop All / switched off
        val after = e.feed(-55, locked = true, startMs = hold * 2, forMs = hold * 4)
        assertEquals(emptyList<Action>(), after.filter { it != Action.NONE })
    }
}
