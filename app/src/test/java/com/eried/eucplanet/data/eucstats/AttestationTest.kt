package com.eried.eucplanet.data.eucstats

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AttestationTest {
    @Test fun stub_returnsEmptyTokenAndEchoesHash() = runBlocking {
        val a = StubAttestation()
        val res = a.token("deadbeef")
        assertEquals("", res.token)
        assertEquals("deadbeef", res.requestHash)
    }
}
