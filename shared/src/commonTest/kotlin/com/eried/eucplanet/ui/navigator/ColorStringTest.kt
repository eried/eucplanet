package com.eried.eucplanet.ui.navigator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The map's page colours and the share palette, read as Color.parseColor reads them. */
class ColorStringTest {
    @Test fun `hex colours read opaque or with their alpha`() {
        assertEquals(0xFFDEDEDE.toInt(), parseColorString("#dedede"))
        assertEquals(0xFF0B0F19.toInt(), parseColorString(mapTypeInitialBg("DARK")))
        assertEquals(0x80E53935.toInt(), parseColorString("#80E53935"))
    }

    @Test fun `names read as Android names them`() {
        assertEquals(0xFF008080.toInt(), parseColorString("teal"))
        assertEquals(0xFF888888.toInt(), parseColorString("Gray"))
    }

    @Test fun `anything else is refused`() {
        assertFailsWith<IllegalArgumentException> { parseColorString("#12345") }
        assertFailsWith<IllegalArgumentException> { parseColorString("#zzzzzz") }
        assertFailsWith<IllegalArgumentException> { parseColorString("blurple") }
    }
}
