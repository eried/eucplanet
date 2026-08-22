package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.LockdownReapply
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownReapplyTest {

    @Test
    fun `armed and wheel came back without legal mode means reapply`() {
        assertTrue(LockdownReapply.shouldReapply(armed = true, wheelReportsLegalOn = false))
    }

    @Test
    fun `armed and wheel already legal means leave it alone`() {
        assertFalse(LockdownReapply.shouldReapply(armed = true, wheelReportsLegalOn = true))
    }

    @Test
    fun `not armed never reapplies`() {
        assertFalse(LockdownReapply.shouldReapply(armed = false, wheelReportsLegalOn = false))
        assertFalse(LockdownReapply.shouldReapply(armed = false, wheelReportsLegalOn = true))
    }
}
