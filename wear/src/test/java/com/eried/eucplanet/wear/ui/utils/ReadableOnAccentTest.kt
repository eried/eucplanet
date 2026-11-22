package com.eried.eucplanet.wear.ui.utils

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableOnAccentTest {
    private val lightOnPrimary = Color(0xFF1A1A1A)

    @Test
    fun theLightThemesTealKeepsItsDarkIcon() {
        assertEquals(lightOnPrimary, readableOn(Color(0xFF26C6DA), lightOnPrimary))
    }

    @Test
    fun aDarkBlueOrBlackAccentGetsAWhiteIcon() {
        assertEquals(Color.White, readableOn(Color(0xFF0D47A1), lightOnPrimary))
        assertEquals(Color.White, readableOn(Color.Black, lightOnPrimary))
    }

    @Test
    fun aPaleAccentWithAWhiteIconGetsABlackOne() {
        assertEquals(Color.Black, readableOn(Color(0xFFFFF59D), Color.White))
    }
}
