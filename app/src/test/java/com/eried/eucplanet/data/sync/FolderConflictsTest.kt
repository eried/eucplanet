package com.eried.eucplanet.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the Backup conflicts warning counts. */
class FolderConflictsTest {

    @Test fun `the same trip in both places is no conflict`() {
        assertEquals(0, countFolderConflicts(mapOf("a.csv" to 100L), mapOf("a.csv" to 100L)))
    }

    @Test fun `a trip that differs between the two is one`() {
        assertEquals(1, countFolderConflicts(
            mapOf("a.csv" to 100L, "b.csv" to 50L),
            mapOf("a.csv" to 120L, "b.csv" to 50L),
        ))
    }

    @Test fun `a trip on one side only is a copy still to make, not a conflict`() {
        assertEquals(0, countFolderConflicts(mapOf("a.csv" to 100L), mapOf("b.csv" to 7L)))
        assertEquals(0, countFolderConflicts(mapOf("a.csv" to 100L), emptyMap()))
    }

    @Test fun `the folder spelling a name in other case is the same trip`() {
        assertEquals(0, countFolderConflicts(mapOf("inmotion_v14.csv" to 9L), mapOf("Inmotion_V14.csv" to 9L)))
        assertEquals(1, countFolderConflicts(mapOf("inmotion_v14.csv" to 9L), mapOf("Inmotion_V14.csv" to 8L)))
    }
}
