package com.eried.eucplanet.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind the combine dialog: picking a far end selects a CONTINUOUS
 * span of trips, sweeping in anything that fell between the two ends.
 *
 * Merging 8pm with 10pm while skipping the 9pm between them would produce a
 * trip whose own middle is missing, so its duration and averages would describe
 * a journey nobody made. This mirrors the arithmetic the dialog does, so the
 * rule is pinned even though the dialog itself is Compose.
 */
class CombineRangeTest {

    /** Same computation as CombineTripsDialog: an inclusive span between two
     *  positions in a chronologically ordered list. */
    private fun span(order: List<String>, anchor: String, farEnd: String?): List<String> {
        val a = order.indexOf(anchor)
        val b = order.indexOf(farEnd)
        if (a < 0 || b < 0) return emptyList()
        return order.subList(minOf(a, b), maxOf(a, b) + 1)
    }

    private val trips = listOf("8pm", "9pm", "10pm", "11pm")

    @Test fun pickingALaterTrip_sweepsInEverythingBetween() {
        assertEquals(listOf("8pm", "9pm", "10pm"), span(trips, "8pm", "10pm"))
    }

    @Test fun pickingAnEarlierTrip_worksTheSameBackwards() {
        assertEquals(listOf("9pm", "10pm", "11pm"), span(trips, "11pm", "9pm"))
    }

    @Test fun theAdjacentTrip_isJustThePair() {
        assertEquals(listOf("8pm", "9pm"), span(trips, "8pm", "9pm"))
    }

    @Test fun theWholeList_isSelectableFromEitherEnd() {
        assertEquals(trips, span(trips, "8pm", "11pm"))
        assertEquals(trips, span(trips, "11pm", "8pm"))
    }

    @Test fun noFarEndChosen_selectsNothing() {
        assertEquals(emptyList<String>(), span(trips, "8pm", null))
    }

    @Test fun aSpanIsNeverJustTheAnchor_soApplyStaysDisabled() {
        // The dialog enables Apply only at size >= 2; picking nothing gives 0.
        assertEquals(0, span(trips, "8pm", null).size)
    }
}
