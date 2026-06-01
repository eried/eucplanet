package com.eried.eucplanet.data.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the CUSTOM BLE command model — hex parsing and the
 * multiline frames blob. The JSON round-trip uses org.json (like SettingsJson)
 * and is exercised by the build, not here. No Android runtime needed.
 */
class CustomBleCommandTest {

    @Test
    fun `parseHexFrame accepts spaces`() {
        assertArrayEquals(
            byteArrayOf(0x4c, 0x6b, 0x41, 0x70),
            CustomBleCommand.parseHexFrame("4c 6b 41 70")
        )
    }

    @Test
    fun `parseHexFrame accepts bare, 0x and separators`() {
        assertArrayEquals(byteArrayOf(0x4c, 0x6b), CustomBleCommand.parseHexFrame("4c6b"))
        assertArrayEquals(byteArrayOf(0x4c, 0x6b), CustomBleCommand.parseHexFrame("0x4c, 0x6b"))
        assertArrayEquals(byteArrayOf(0x53, 0x65), CustomBleCommand.parseHexFrame("53:65"))
    }

    @Test
    fun `parseHexFrame rejects odd length`() {
        assertNull(CustomBleCommand.parseHexFrame("4c6"))
    }

    @Test
    fun `parseHexFrame rejects non-hex`() {
        assertNull(CustomBleCommand.parseHexFrame("zz"))
        assertNull(CustomBleCommand.parseHexFrame("4c xx"))
    }

    @Test
    fun `parseHexFrame blank is null`() {
        assertNull(CustomBleCommand.parseHexFrame("   "))
    }

    @Test
    fun `parseFrames splits non-blank lines`() {
        val frames = CustomBleCommand.parseFrames("53 65\n\n4c 64 ")!!
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x53, 0x65), frames[0])
        assertArrayEquals(byteArrayOf(0x4c, 0x64), frames[1])
    }

    @Test
    fun `parseFrames returns null when any line invalid`() {
        assertNull(CustomBleCommand.parseFrames("53 65\nzz"))
    }

    @Test
    fun `bytesToHex and framesToText`() {
        assertEquals("4c 6b", CustomBleCommand.bytesToHex(byteArrayOf(0x4c, 0x6b)))
        assertEquals(
            "4c 6b\n53 65",
            CustomBleCommand.framesToText(listOf(byteArrayOf(0x4c, 0x6b), byteArrayOf(0x53, 0x65)))
        )
    }

    @Test
    fun `isCustomBleId recognises the prefix`() {
        assertTrue(CustomBleCommand.isCustomBleId("B:abc"))
        assertFalse(CustomBleCommand.isCustomBleId("HORN"))
    }

    @Test
    fun `low-beam preset hex equals SetLightON`() {
        assertArrayEquals(
            "SetLightON".toByteArray(Charsets.US_ASCII),
            CustomBleCommand.parseHexFrame("53 65 74 4c 69 67 68 74 4f 4e")
        )
    }

    private fun cmd(family: String, frames: List<ByteArray> = listOf(byteArrayOf(0x62))) =
        CustomBleCommand("B:1", "x", "Campaign", family, frames)

    @Test
    fun `resolveForDispatch returns command when family matches and connected`() {
        val c = cmd("veteran")
        assertEquals(c, CustomBleCommand.resolveForDispatch(mapOf(c.id to c), "B:1", "veteran"))
    }

    @Test
    fun `resolveForDispatch is null on wrong family`() {
        val c = cmd("veteran")
        assertNull(CustomBleCommand.resolveForDispatch(mapOf(c.id to c), "B:1", "kingsong"))
    }

    @Test
    fun `resolveForDispatch is null when disconnected`() {
        val c = cmd("veteran")
        assertNull(CustomBleCommand.resolveForDispatch(mapOf(c.id to c), "B:1", null))
    }

    @Test
    fun `resolveForDispatch is null when command has no sendable frames`() {
        val c = cmd("veteran", emptyList())
        assertNull(CustomBleCommand.resolveForDispatch(mapOf(c.id to c), "B:1", "veteran"))
    }

    @Test
    fun `resolveForDispatch is null when key missing`() {
        assertNull(CustomBleCommand.resolveForDispatch(emptyMap(), "B:1", "veteran"))
    }
}
