package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.ExtendPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind Extend, against the code the dialog itself runs.
 *
 * What is being defended: a merge must never contain the same samples twice.
 * That happens two ways - extending into a piece of the trip you are standing
 * on, and reaching past an unrelated combined trip so the range swallows both
 * it and the pieces it was made from. Either produces a trip whose distance and
 * duration describe a ride nobody made, and there is no undo.
 */
class ExtendPlanTest {

    private var nextId = 1L

    /** A trip from hour [h] lasting [minutes], on a fixed day. */
    private fun trip(h: Int, minutes: Int = 1): TripRecord {
        val start = h * 3_600_000L
        return TripRecord(
            id = nextId++,
            startTime = start,
            endTime = start + minutes * 60_000L,
            fileName = "trip_%02d.csv".format(h),
        )
    }

    /** A combined trip covering [from] to the end of [to]. */
    private fun joined(from: TripRecord, to: TripRecord) = TripRecord(
        id = nextId++,
        startTime = from.startTime,
        endTime = to.endTime,
        fileName = "join_${from.fileName}",
    )

    private fun ordered(vararg t: TripRecord) = t.sortedBy { it.startTime }

    // --- overlap -----------------------------------------------------------

    @Test fun `a piece inside the anchor overlaps it`() {
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        assertTrue(ExtendPlan.overlaps(join, eight))
        assertTrue(ExtendPlan.overlaps(join, nine))
    }

    @Test fun `standing on a piece, the trip containing it overlaps too`() {
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        assertTrue(ExtendPlan.overlaps(eight, join))
    }

    @Test fun `rides that merely touch are neighbours, not overlaps`() {
        val first = TripRecord(id = 1, startTime = 0, endTime = 60_000, fileName = "a.csv")
        val second = TripRecord(id = 2, startTime = 60_000, endTime = 120_000, fileName = "b.csv")
        assertFalse(ExtendPlan.overlaps(first, second))
        assertFalse(ExtendPlan.overlaps(second, first))
    }

    @Test fun `a trip never overlaps itself`() {
        val t = trip(8)
        assertFalse(ExtendPlan.overlaps(t, t))
    }

    @Test fun `a trip still recording, with no end, is a point in time`() {
        val open = TripRecord(id = 9, startTime = 5_000, endTime = null, fileName = "open.csv")
        val before = TripRecord(id = 10, startTime = 0, endTime = 4_000, fileName = "before.csv")
        assertFalse(ExtendPlan.overlaps(open, before))
    }

    // --- containment -------------------------------------------------------

    @Test fun `a piece is inside its combined trip`() {
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val all = ordered(eight, nine, join)
        assertTrue(ExtendPlan.isInsideAnother(eight, all))
        assertTrue(ExtendPlan.isInsideAnother(nine, all))
        assertFalse(ExtendPlan.isInsideAnother(join, all))
    }

    @Test fun `two trips of the same span do not swallow each other`() {
        val a = TripRecord(id = 1, startTime = 0, endTime = 60_000, fileName = "a.csv")
        val b = TripRecord(id = 2, startTime = 0, endTime = 60_000, fileName = "b.csv")
        val all = listOf(a, b)
        assertFalse(ExtendPlan.isInsideAnother(a, all))
        assertFalse(ExtendPlan.isInsideAnother(b, all))
    }

    // --- what the pickers offer -------------------------------------------

    @Test fun `plain trips offer their neighbours both ways`() {
        val six = trip(6); val seven = trip(7); val eight = trip(8)
        val all = ordered(six, seven, eight)
        val reach = ExtendPlan.reach(all, seven)
        assertEquals(listOf(all.indexOf(six)), reach.back)
        assertEquals(listOf(all.indexOf(eight)), reach.forward)
        assertFalse(reach.isDeadEnd)
    }

    @Test fun `a combined trip is not offered its own pieces`() {
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val all = ordered(eight, nine, join)
        val reach = ExtendPlan.reach(all, join)
        assertTrue(reach.back.isEmpty())
        assertTrue(reach.forward.isEmpty())
        assertTrue(reach.isDeadEnd)
    }

    @Test fun `standing on a piece, neither the sibling nor the whole is offered`() {
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val all = ordered(eight, nine, join)
        assertTrue(ExtendPlan.reach(all, eight).isDeadEnd)
    }

    @Test fun `an unrelated merge is offered, but the pieces it holds are not`() {
        val six = trip(6); val seven = trip(7)
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val eleven = trip(11)
        val all = ordered(six, seven, eight, nine, join, eleven)
        val reach = ExtendPlan.reach(all, six)
        val offered = (reach.back + reach.forward).map { all[it].fileName }
        assertTrue(offered.contains(join.fileName))
        assertTrue(offered.contains(seven.fileName))
        assertTrue(offered.contains(eleven.fileName))
        assertFalse(offered.contains(eight.fileName))
        assertFalse(offered.contains(nine.fileName))
    }

    @Test fun `reach counts pickable trips, so pieces do not hide the rides beyond`() {
        // Ten plain trips, each with a combined twin sitting on top of it, so
        // half the list is unpickable. The rider should still see eight ends.
        val plain = (1..10).map { trip(it) }
        val joins = plain.map { joined(it, it) }   // same span: neither hides the other
        val anchor = trip(0)
        val all = ordered(*(plain + joins + listOf(anchor)).toTypedArray())
        val reach = ExtendPlan.reach(all, anchor)
        assertEquals(ExtendPlan.REACH, reach.forward.size)
    }

    // --- what a chosen span merges ----------------------------------------

    @Test fun `a span sweeps in everything between the two ends`() {
        val six = trip(6); val seven = trip(7); val eight = trip(8)
        val all = ordered(six, seven, eight)
        val merge = ExtendPlan.merge(all, six, all.indexOf(six), all.indexOf(eight))
        assertEquals(listOf(six.fileName, seven.fileName, eight.fileName), merge.trips.map { it.fileName })
        assertEquals(0, merge.skipped)
    }

    @Test fun `reaching from the join, its own pieces in the span are stepped over`() {
        val six = trip(6); val seven = trip(7)
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val all = ordered(six, seven, eight, nine, join)
        // Ordered: six, seven, eight, join, nine - so only the 08:00 piece lies
        // between 06:00 and the join; the 09:00 one is past the anchor.
        val merge = ExtendPlan.merge(all, join, all.indexOf(six), all.indexOf(join))
        assertEquals(listOf(six.fileName, seven.fileName, join.fileName), merge.trips.map { it.fileName })
        assertEquals(1, merge.skipped)
    }

    @Test fun `a span past an unrelated merge takes the merge, not its pieces`() {
        // The case the containment rule exists for: nothing here overlaps the
        // anchor, so without it the range would merge that hour twice.
        val six = trip(6); val seven = trip(7)
        val eight = trip(8); val nine = trip(9)
        val join = joined(eight, nine)
        val all = ordered(six, seven, eight, nine, join)
        val merge = ExtendPlan.merge(all, six, all.indexOf(six), all.lastIndex)
        assertEquals(listOf(six.fileName, seven.fileName, join.fileName), merge.trips.map { it.fileName })
        assertEquals(2, merge.skipped)
    }

    @Test fun `the anchor is always in its own merge`() {
        val six = trip(6); val seven = trip(7)
        val join = joined(six, seven)
        val all = ordered(six, seven, join)
        // Even anchored on a piece, which is "inside another" by the rule.
        val merge = ExtendPlan.merge(all, six, all.indexOf(six), all.indexOf(six))
        assertEquals(listOf(six.fileName), merge.trips.map { it.fileName })
    }

    @Test fun `picking nothing leaves a merge of one, which the dialog refuses`() {
        val six = trip(6); val seven = trip(7)
        val all = ordered(six, seven)
        val merge = ExtendPlan.merge(all, six, all.indexOf(six), all.indexOf(six))
        assertEquals(1, merge.trips.size)
    }

    @Test fun `reaching backwards works the same as forwards`() {
        val six = trip(6); val seven = trip(7); val eight = trip(8)
        val all = ordered(six, seven, eight)
        val back = ExtendPlan.merge(all, eight, all.indexOf(six), all.indexOf(eight))
        assertEquals(3, back.trips.size)
    }

    @Test fun `an anchor missing from the list merges nothing`() {
        val all = ordered(trip(6), trip(7))
        val stranger = trip(20)
        assertEquals(0, ExtendPlan.merge(all, stranger, 0, 1).trips.size)
        assertTrue(ExtendPlan.reach(all, stranger).isDeadEnd)
    }
}
