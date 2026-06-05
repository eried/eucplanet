package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.mergeFinalizeStatuses
import org.junit.Assert.assertEquals
import org.junit.Test

class FinalizeStatusesTest {
    private val base = TripRecord(fileName = "t.csv", uploadStatus = 0, eucstatsStatus = 0)

    @Test fun bothEnabled_setsBothWithoutClobber() {
        val r = mergeFinalizeStatuses(base, willSync = true, willEucstats = true)
        assertEquals(1, r.uploadStatus)
        assertEquals(1, r.eucstatsStatus)
    }

    @Test fun folderOnly_leavesEucstatsUntouched() {
        val r = mergeFinalizeStatuses(base, willSync = true, willEucstats = false)
        assertEquals(1, r.uploadStatus)
        assertEquals(0, r.eucstatsStatus)
    }

    @Test fun eucstatsOnly_leavesFolderUntouched() {
        val r = mergeFinalizeStatuses(base, willSync = false, willEucstats = true)
        assertEquals(0, r.uploadStatus)
        assertEquals(1, r.eucstatsStatus)
    }

    @Test fun neither_leavesBothUnchanged() {
        val r = mergeFinalizeStatuses(base, willSync = false, willEucstats = false)
        assertEquals(0, r.uploadStatus)
        assertEquals(0, r.eucstatsStatus)
    }
}
