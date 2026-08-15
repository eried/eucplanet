package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards that a recognised wheel's cell count survives the trip through
 * [CompositeWheelAdapter].
 *
 * The composite is what the repository holds; the family adapters sit behind
 * it, and every member it does not forward falls back to the interface default.
 * That is how `nominalPackVoltage` first shipped: each family answered
 * correctly, the composite dropped the answer on the floor, and riders were
 * asked to count cells their wheel had already stated. Nothing about it looked
 * broken, which is why it needs a test.
 *
 * A structural check cannot catch this: the compiler emits a stub for every
 * interface member on every implementing class, so a missing override is
 * invisible to reflection. Only asking the composite for the value works.
 */
class CompositeWheelAdapterDelegationTest {

    private fun composite() = CompositeWheelAdapter(
        InMotionV2Adapter(),
        InMotionV1Adapter(),
        KingsongAdapter(),
        BegodeAdapter(),
        VeteranAdapter(),
        NinebotAdapter(),
    )

    private fun cellsAfterConnecting(deviceName: String?): Int? =
        composite().apply { notifyConnectingTo(deviceName) }.seriesCells

    @Test
    fun `every family's cell count reaches the app`() {
        // One recognised wheel per family, with the count its charged pack
        // voltage gives: 126 V is 30S, 134 V is 32S, 100 V is 24S, 84 V is 20S.
        val expected = mapOf(
            "KS-S22-1234" to 30,
            "Begode Master" to 32,
            "Sherman" to 24,
            "Ninebot Z10" to 20,
            "V8F" to 20,
        )
        for ((name, cells) in expected) {
            assertEquals(name, cells, cellsAfterConnecting(name))
        }
    }

    @Test
    fun `an unrecognised wheel leaves the count to the rider`() {
        // Null is what makes the repository fall back to the rider's setting,
        // so a guess is never presented as the wheel's own answer.
        assertNull(cellsAfterConnecting("RW"))
        assertNull(cellsAfterConnecting(null))
    }
}
