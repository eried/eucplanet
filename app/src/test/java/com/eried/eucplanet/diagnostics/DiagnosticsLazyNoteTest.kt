package com.eried.eucplanet.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A wheel sends a frame several times a second for as long as it is connected,
 * and every adapter writes that frame to the service mode log as a hex dump.
 * With service mode off, which is nearly always, the line is thrown away, so
 * the dump must not be built in the first place.
 */
class DiagnosticsLazyNoteTest {

    @Before
    @After
    fun reset() {
        DiagnosticsLogger.disable()
        DiagnosticsLogger.clear()
    }

    @Test
    fun `with service mode off the text of a note is never built`() {
        var built = false
        DiagnosticsLogger.note { built = true; "frame body=00 01 02" }
        assertFalse(built)
        assertTrue(DiagnosticsLogger.entries.value.none { it.kind == DiagnosticsLogger.Kind.NOTE })
    }

    @Test
    fun `with service mode on the note is recorded as written`() {
        DiagnosticsLogger.enable()
        DiagnosticsLogger.note { "frame body=00 01 02" }
        val notes = DiagnosticsLogger.entries.value.filter { it.kind == DiagnosticsLogger.Kind.NOTE }
        assertEquals(listOf("frame body=00 01 02"), notes.map { it.text })
    }

    @Test
    fun `no note in the app formats a frame before asking whether anyone listens`() {
        // The eager form, note("..."), takes a finished string. A call that
        // builds a hex dump or formats numbers belongs in the lazy form.
        val root = File("src/main/java/com/eried/eucplanet")
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val src = file.readText()
            var from = 0
            while (true) {
                val at = src.indexOf("DiagnosticsLogger.note(", from)
                if (at < 0) break
                val open = at + "DiagnosticsLogger.note".length
                var depth = 0
                var end = open
                while (end < src.length) {
                    when (src[end]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    end++
                }
                val arg = src.substring(open, end)
                if ("joinToString" in arg || "toHex" in arg || "hex(" in arg) {
                    offenders += file.name + ":" + (src.substring(0, at).count { it == '\n' } + 1)
                }
                from = end
            }
        }
        assertEquals("notes that build a dump eagerly", emptyList<String>(), offenders)
    }
}
