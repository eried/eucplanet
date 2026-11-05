package com.eried.eucplanet.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Pins [JavaFormat] (the iOS formatter) to what `java.lang.String.format` prints. */
class JavaFormatTest {
    private val us = java.util.Locale.US
    private val de = java.util.Locale.GERMANY

    private fun check(fmt: String, vararg args: Any?) {
        assertEquals("$fmt ${args.toList()}", java.lang.String.format(us, fmt, *args), JavaFormat.format(JavaFormat.Symbols.US, fmt, *args))
        assertEquals("de $fmt ${args.toList()}", java.lang.String.format(de, fmt, *args), JavaFormat.format(JavaFormat.Symbols(',', '.'), fmt, *args))
    }

    @Test
    fun everySpecifierTheAppUses() {
        val doubles = listOf(0.0, -0.0, 0.25, 0.15, 0.05, 1.005, 2.5, -2.5, 9.995, 99.95, 123.456, -0.04, 1e-7, 12345678.9, 1e21, 0.1 + 0.2)
        for (f in listOf("%.0f", "%.1f", "%.2f", "%.3f", "%.4f", "%.5f", "%.6f", "%+.0f", "%+.1f", "%+.2f", "%f", "%,.1f", "%8.2f", "%-8.1f|", "%08.2f", "%e", "%.2e", "%(.1f"))
            for (d in doubles) check(f, d)
        for (f in listOf("%.1f", "%.3f", "%.0f", "%+.1f")) for (x in listOf(3.7f, 0.1f, -12.45f, 1234.5f, 0.05f)) check(f, x)
        val ints = listOf<Any>(0, 7, -7, 255, -1, Int.MAX_VALUE, Int.MIN_VALUE, 12345L, -12345L, Long.MIN_VALUE, 5.toByte(), (-3).toByte(), (-2).toShort())
        for (f in listOf("%d", "%02d", "%03d", "%04d", "%05d", "%06d", "%+d", "%,d", "% d", "%-5d|", "%(d", "%02x", "%02X", "%04x", "%04X", "%06X", "%08X", "%x", "%#x", "%o"))
            for (n in ints) check(f, n)
        check("%s and %s", "a", null)
        check("%S %.2s %5s|%-5s|", "abc", "abcdef", "x", "y")
        check("%2\$s %1\$s %<s", "one", "two")
        check("100%% done%n")
        check("%c%c %b %b", 'x', 65, true, null)
    }

    @Test
    fun randomDoublesMatch() {
        val r = Random(42)
        repeat(20_000) {
            val d = (r.nextDouble() - 0.5) * java.lang.Math.pow(10.0, r.nextInt(-6, 9).toDouble())
            for (f in listOf("%.0f", "%.1f", "%.2f", "%.4f")) check(f, d)
            check("%.1f", d.toFloat())
        }
    }
}
