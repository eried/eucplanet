package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EucStatsTripFieldsTest {
    @Test fun newTripRecord_hasEucstatsDefaults() {
        val t = TripRecord(fileName = "trip_x.csv")
        assertEquals(0, t.eucstatsStatus)
        assertEquals(0, t.sampleCount)
        assertEquals(false, t.isMockLocation)
        assertNull(t.tripUuid)
        assertNull(t.wheelMetaJson)
        assertNull(t.eucstatsValidation)
    }
}
