package com.eried.eucplanet.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Every dropdown field in the app is one line high.
 *
 * A dropdown shows its current choice in a read-only text field. Left to
 * wrap, a long choice ("Phone flashlight", a long translation) makes that one
 * field taller than the one beside it, and a row of two pickers stops lining
 * up. Half the dropdowns had `singleLine` and half did not, which is how the
 * volume key pickers ended up two lines high next to one-line neighbours.
 *
 * Reads the sources, because nothing at runtime ties the dropdowns together:
 * each screen builds its own field.
 */
class DropdownSingleLineTest {

    @Test
    fun `every read-only dropdown field is single line`() {
        val offenders = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                Regex("""OutlinedTextField\(""").findAll(text).mapNotNull { m ->
                    val call = callAt(text, m.range.last + 1)
                    val isDropdown = "readOnly = true" in call && "menuAnchor" in call
                    if (isDropdown && "singleLine" !in call && "maxLines" !in call) {
                        "${file.name}:${text.substring(0, m.range.first).count { it == '\n' } + 1}"
                    } else null
                }
            }
            .toList()
        assertEquals("dropdown fields that can wrap to two lines", emptyList<String>(), offenders)
    }

    /** The call's argument list, from just after its opening parenthesis to the matching close. */
    private fun callAt(text: String, start: Int): String {
        var depth = 1
        var i = start
        while (depth > 0 && i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, i)
    }
}
