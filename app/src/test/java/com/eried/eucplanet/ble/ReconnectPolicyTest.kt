package com.eried.eucplanet.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chasing a wheel that dropped, and leaving one alone that was dismissed.
 *
 * Both mistakes are expensive. Not reconnecting after a blip costs the rest of
 * the ride's telemetry, on a ride that cannot be repeated. Reconnecting to a
 * wheel the rider disconnected on purpose wakes it up again, holds the link,
 * and drains a battery in a bag.
 */
class ReconnectPolicyTest {

    private val wheel = "AA:BB:CC:DD:EE:FF"
    private val other = "11:22:33:44:55:66"

    // --- after a disconnect ------------------------------------------------

    @Test fun `a broken connection is chased`() {
        // 133 is the everyday GATT error for a link that dropped.
        assertTrue(ReconnectPolicy.shouldRetryAfterDisconnect(armed = true, target = wheel, status = 133))
        assertTrue(ReconnectPolicy.shouldRetryAfterDisconnect(armed = true, target = wheel, status = 8))
    }

    @Test fun `a disconnect the rider asked for is left alone`() {
        assertFalse(ReconnectPolicy.shouldRetryAfterDisconnect(
            armed = true, target = wheel, status = ReconnectPolicy.STATUS_CLEAN))
    }

    @Test fun `nothing is chased once auto-reconnect is cancelled`() {
        assertFalse(ReconnectPolicy.shouldRetryAfterDisconnect(armed = false, target = wheel, status = 133))
    }

    @Test fun `with no wheel armed there is nothing to chase`() {
        assertFalse(ReconnectPolicy.shouldRetryAfterDisconnect(armed = true, target = null, status = 133))
    }

    // --- by the time the delayed attempt runs ------------------------------

    @Test fun `still armed, still gone, Bluetooth on - go`() {
        assertTrue(ReconnectPolicy.eligible(
            armed = true, target = wheel, address = wheel, connected = false, adapterOn = true))
    }

    @Test fun `the rider reconnected in the meantime`() {
        assertFalse(ReconnectPolicy.eligible(
            armed = true, target = wheel, address = wheel, connected = true, adapterOn = true))
    }

    @Test fun `the rider cancelled in the meantime`() {
        assertFalse(ReconnectPolicy.eligible(
            armed = false, target = wheel, address = wheel, connected = false, adapterOn = true))
    }

    @Test fun `the rider switched to another wheel in the meantime`() {
        assertFalse(ReconnectPolicy.eligible(
            armed = true, target = other, address = wheel, connected = false, adapterOn = true))
    }

    @Test fun `Bluetooth went off again`() {
        assertFalse(ReconnectPolicy.eligible(
            armed = true, target = wheel, address = wheel, connected = false, adapterOn = false))
    }

    @Test fun `a queued attempt for a wheel no longer armed does nothing`() {
        assertFalse(ReconnectPolicy.eligible(
            armed = true, target = null, address = wheel, connected = false, adapterOn = true))
    }
}
