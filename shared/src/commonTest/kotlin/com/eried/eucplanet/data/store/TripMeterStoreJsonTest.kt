package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.TripMeterSplit
import com.eried.eucplanet.data.model.TripMeterState
import com.eried.eucplanet.platform.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The trip meter's saved blob reads back what was written, on both phones,
 * and a blob from an older or broken write still opens.
 */
class TripMeterStoreJsonTest {

    private val state = TripMeterState(
        distanceKm = 23.5f,
        activeMs = 3_600_000L,
        startedAtMs = 1_700_000_000_000L,
        splits = listOf(
            TripMeterSplit(1, 10f, 1_500_000L, 1_500_000L, 24f, 41.5f, 88),
            TripMeterSplit(2, 20f, 3_000_000L, 1_500_000L, 24f, 38f, -1),
        ),
    )

    @Test
    fun roundTripsThroughTheJsonText() {
        val text = TripMeterStore.toJson(state).toString()
        assertEquals(state, TripMeterStore.fromJson(JSONObject(text)))
    }

    @Test
    fun writesAndroidsKeys() {
        val o = TripMeterStore.toJson(state)
        assertEquals(23.5, o.optDouble("distanceKm"), 0.0001)
        assertEquals(3_600_000L, o.optLong("activeMs"))
        val first = o.optJSONArray("splits")!!.optJSONObject(0)!!
        assertEquals(88, first.optInt("batteryPctAtMark"))
        assertEquals(41.5, first.optDouble("segmentMaxKmh"), 0.0001)
    }

    @Test
    fun missingFieldsReadAsAFreshMeter() {
        val restored = TripMeterStore.fromJson(JSONObject("""{"distanceKm":4.25,"splits":[{"markDistanceKm":10}]}"""))
        assertEquals(4.25f, restored.distanceKm, 0.0001f)
        assertEquals(0L, restored.activeMs)
        assertEquals(1, restored.splits.single().index)
        assertEquals(-1, restored.splits.single().batteryPctAtMark)
    }
}
