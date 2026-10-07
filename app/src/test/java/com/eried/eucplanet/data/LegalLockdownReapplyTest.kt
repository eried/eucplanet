package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.LockdownReapply
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownReapplyTest {

    @Test
    fun `engaged and wheel came back without legal mode means reapply`() {
        assertTrue(LockdownReapply.shouldReapply(engaged = true, wheelReportsLegalOn = false))
    }

    @Test
    fun `engaged and wheel already legal means leave it alone`() {
        assertFalse(LockdownReapply.shouldReapply(engaged = true, wheelReportsLegalOn = true))
    }

    /**
     * Armed but not yet engaged must never switch legal mode on by itself. The
     * resident setting waits for the rider to turn legal mode on; forcing it
     * here would defeat the whole point of the mode being latent.
     */
    @Test
    fun `not engaged never reapplies`() {
        assertFalse(LockdownReapply.shouldReapply(engaged = false, wheelReportsLegalOn = false))
        assertFalse(LockdownReapply.shouldReapply(engaged = false, wheelReportsLegalOn = true))
    }
}
