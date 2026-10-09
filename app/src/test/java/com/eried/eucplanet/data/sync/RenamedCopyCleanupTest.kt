package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.sync.RenamedCopyCleanup.Entry
import org.junit.Assert.assertEquals
import org.junit.Test

class RenamedCopyCleanupTest {

    /** Content by name; equal strings stand for equal bytes. */
    private fun plan(vararg files: Pair<String, String>): List<String> {
        val content = files.toMap()
        return RenamedCopyCleanup.plan(files.map { Entry(it.first, it.second.length.toLong()) }) { a, b ->
            content.getValue(a.name) == content.getValue(b.name)
        }.sorted()
    }

    @Test fun `copies identical to the original go, whatever the original's case`() {
        assertEquals(
            listOf("inmotion_v14_x (1).csv", "inmotion_v14_x (2).csv"),
            plan("Inmotion_V14_x.csv" to "ride", "inmotion_v14_x (1).csv" to "ride", "inmotion_v14_x (2).csv" to "ride"),
        )
    }

    @Test fun `a copy that differs from the original stays once, its repeats go`() {
        assertEquals(
            listOf("t (2).csv", "t (10).csv").sorted(),
            plan("t.csv" to "old", "t (1).csv" to "new", "t (2).csv" to "new", "t (10).csv" to "new"),
        )
    }

    @Test fun `numbers sort as numbers, so the lowest copy is the one kept`() {
        assertEquals(listOf("t (10).csv"), plan("t (10).csv" to "a", "t (9).csv" to "a"))
    }

    @Test fun `a copy with no original and no twin is the only one, and stays`() {
        assertEquals(emptyList<String>(), plan("t (1).csv" to "only"))
    }

    @Test fun `same size but different bytes is not a duplicate`() {
        assertEquals(emptyList<String>(), plan("t.csv" to "abc", "t (1).csv" to "xyz"))
    }

    @Test fun `ordinary trips are never touched`() {
        assertEquals(emptyList<String>(), plan("a.csv" to "x", "b.csv" to "x", "c (note).csv" to "x"))
    }
}
