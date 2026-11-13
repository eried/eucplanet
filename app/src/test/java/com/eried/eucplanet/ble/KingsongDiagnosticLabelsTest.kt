package com.eried.eucplanet.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Service Mode labels are opcode-derived so a tester can report a button by
 * name and the packet is known. For the headlight (type 0x73) the suffix is
 * the wire byte at offset 2, where 0x12 = on, 0x13 = off, 0x14 = auto.
 */
class KingsongDiagnosticLabelsTest {

    private val light = KingsongAdapter().getDiagnosticCommands()
        .filter { it.label.startsWith("T73_") }

    @Test fun `each headlight label names the byte its frame carries`() {
        assertEquals(3, light.size)
        for (cmd in light) {
            val suffix = cmd.label.removePrefix("T73_").toInt(16)
            assertEquals("${cmd.label} (${cmd.description})", suffix, cmd.bytes[2].toInt() and 0xFF)
            assertEquals(0x73, cmd.bytes[16].toInt() and 0xFF)
        }
    }

    @Test fun `each headlight description matches the frame the builder sends`() {
        fun described(word: String) = light.single { it.description.contains(word) }.bytes
        assertTrue(described("off").contentEquals(KingsongCommands.setLightMode(0)))
        assertTrue(described(" on").contentEquals(KingsongCommands.setLightMode(1)))
        assertTrue(described("auto").contentEquals(KingsongCommands.setLightMode(2)))
    }
}
