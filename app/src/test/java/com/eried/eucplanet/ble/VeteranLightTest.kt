package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pins the Veteran headlight commands.
 *
 * The in-app light toggle drives the HIGH beam by default: a two-frame
 * `LkAp` + `LdAp` pair decoded from a LeaperKim-app btsnoop on a Lynx S
 * (high beam on @cap25.3s, off @cap28.3s). The legacy ASCII `SetLightON/OFF`
 * (low beam) stays available as a commented fallback in [VeteranAdapter].
 * Pure-JVM test; no Android runtime needed.
 */
class VeteranLightTest {

    private fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }

    private val HIGHBEAM_ON_LKAP = "4c 6b 41 70 0d 01 80 80 01 57 ed 3b d5"
    private val HIGHBEAM_ON_LDAP = "4c 64 41 70 0d 01 00 80 01 6f f8 32 f9"
    private val HIGHBEAM_OFF_LKAP = "4c 6b 41 70 0d 01 80 80 00 20 ea 0b 43"
    private val HIGHBEAM_OFF_LDAP = "4c 64 41 70 0d 01 00 80 00 18 ff 02 6f"

    @Test
    fun `high beam on is the captured LkAp + LdAp pair`() {
        assertEquals(HIGHBEAM_ON_LKAP, VeteranCommands.setHighBeam(true).hex())
        assertEquals(HIGHBEAM_ON_LDAP, VeteranCommands.setHighBeamCompanion(true).hex())
    }

    @Test
    fun `high beam off is the captured LkAp + LdAp pair`() {
        assertEquals(HIGHBEAM_OFF_LKAP, VeteranCommands.setHighBeam(false).hex())
        assertEquals(HIGHBEAM_OFF_LDAP, VeteranCommands.setHighBeamCompanion(false).hex())
    }

    @Test
    fun `veteran light toggle drives high beam by default, with companion`() {
        val adapter = VeteranAdapter()
        assertEquals(HIGHBEAM_ON_LKAP, adapter.setLight(true).hex())
        val followup = adapter.setLightFollowup(true)
        assertNotNull("high beam needs the LdAp companion", followup)
        assertEquals(HIGHBEAM_ON_LDAP, followup!!.hex())
    }
}
