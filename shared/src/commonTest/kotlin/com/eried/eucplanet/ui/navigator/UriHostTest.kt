package com.eried.eucplanet.ui.navigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The group view's relay host, read the way android.net.Uri reads it. */
class UriHostTest {
    @Test fun `scheme - port - path and user info drop away`() {
        assertEquals("relay.example.com", uriHost("wss://relay.example.com/ws"))
        assertEquals("relay.example.com", uriHost("wss://relay.example.com:8443/ws?x=1"))
        assertEquals("host", uriHost("wss://rider@host:8443"))
        assertEquals("[::1]", uriHost("ws://[::1]:9000/"))
        assertEquals("café.example", uriHost("https://caf%C3%A9.example/"))
    }

    @Test fun `no authority means no host`() {
        assertNull(uriHost("relay.example.com"))
        assertNull(uriHost("wss:relay.example.com"))
    }
}
