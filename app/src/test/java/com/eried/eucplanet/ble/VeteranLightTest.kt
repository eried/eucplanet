package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Veteran headlight commands.
 *
 * The in-app light toggle drives the HIGH beam by default: a two-frame
 * `LkAp` + `LdAp` pair decoded from a LeaperKim-app btsnoop on a Lynx S
 * (high beam on @cap25.3s, off @cap28.3s). The legacy ASCII `SetLightON/OFF`
 * (low beam) is selected for the NOSFET Aeon only.
 * Pure-JVM test; no Android runtime needed.
 */
class VeteranLightTest {
    @Test
    fun `Aeon uses single ASCII commands and disconnect restores default mapping`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("NOSFET Aeon")
        for (on in listOf(true, false)) {
            assertEquals(if (on) "SetLightON" else "SetLightOFF", adapter.setLight(on).toString(Charsets.US_ASCII))
            assertNull(adapter.setLightFollowup(on))
        }
        adapter.onDisconnect()
        assertEquals(HIGHBEAM_ON_LKAP, adapter.setLight(true).hex())
        assertEquals(HIGHBEAM_ON_LDAP, adapter.setLightFollowup(true)!!.hex())
    }

    @Test
    fun `generic BLE name selects Aeon commands after captured model telemetry`() {
        val adapter = VeteranAdapter()
        adapter.notifyConnectingTo("NF7445")
        assertEquals(HIGHBEAM_ON_LKAP, adapter.setLight(true).hex())
        // Owner capture 2026-09-07, Aeon 503002; original CRC included.
        val frame = ("dc 5a 5c 47 33 f0 00 00 d4 b2 00 01 d6 9e 00 01 " +
            "00 00 0d 22 04 6b 00 00 01 5e 01 90 ac da 07 b4 1f 14 00 00 " +
            "80 c8 00 00 80 80 80 80 80 80 08 00 00 80 50 00 28 41 23 1e " +
            "00 00 00 00 80 80 28 00 3e 91 32 80 00 80 80 49 e9 ce e1")
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        frame.toList().chunked(20).forEach { adapter.onRawNotification(it.toByteArray()) }
        // Finish the initial default transaction even if identification arrives mid-write.
        assertEquals(HIGHBEAM_ON_LDAP, adapter.setLightFollowup(true)!!.hex())
        assertEquals("SetLightON", adapter.setLight(true).toString(Charsets.US_ASCII))
        assertNull(adapter.setLightFollowup(true))
    }

    @Test
    fun `all other models retain the existing light pair`() {
        for (model in VeteranModel.entries.filter { it != VeteranModel.NOSFET_AEON } + null) {
            val profile = VeteranControlProfile.forModel(model)
            assertEquals(HIGHBEAM_ON_LKAP, profile.setLight(true).hex())
            assertEquals(HIGHBEAM_ON_LDAP, profile.setLightFollowup(true)!!.hex())
        }
    }

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
