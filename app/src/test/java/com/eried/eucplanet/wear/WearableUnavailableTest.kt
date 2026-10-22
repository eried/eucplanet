package com.eried.eucplanet.wear

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * A phone whose Play Services has no Wearable module (GrapheneOS with a
 * sandboxed Play, no-Wear phones, bare emulators) answers every Wearable
 * call with status 17. WearMapBridge used to retry its tile reconcile on
 * that every 30 s for the life of the process, with a stack trace each
 * time. The classifier below is what stops the loop, so it has to tell the
 * permanent case from a watch that is merely out of reach.
 */
class WearableUnavailableTest {

    @Test fun `status 17 wrapped by Tasks await is permanent`() {
        val e = ExecutionException(ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED)))
        assertTrue(isWearableUnavailable(e))
    }

    @Test fun `a plain failure is not`() {
        assertFalse(isWearableUnavailable(ExecutionException(IOException("node gone"))))
        assertFalse(isWearableUnavailable(ApiException(Status(CommonStatusCodes.TIMEOUT))))
    }
}
