package com.eried.eucplanet.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSyncPolicyTest {
    @Test fun missingOnPhone_downloads() {
        val p = FileSyncPolicy.decide(localSizes = emptyMap(), remoteSizes = mapOf("a.json" to 10L))
        assertEquals(listOf("a.json"), p.download)
        assertEquals(emptyList<String>(), p.upload); assertEquals(emptyList<String>(), p.conflicts)
    }
    @Test fun missingOnDropbox_uploads() {
        val p = FileSyncPolicy.decide(localSizes = mapOf("b.json" to 5L), remoteSizes = emptyMap())
        assertEquals(listOf("b.json"), p.upload)
    }
    @Test fun sameNameSameSize_isNoOp() {
        val p = FileSyncPolicy.decide(mapOf("c.json" to 7L), mapOf("c.json" to 7L))
        assertEquals(emptyList<String>(), p.upload + p.download + p.conflicts)
    }
    @Test fun sameNameDifferentSize_conflicts() {
        val p = FileSyncPolicy.decide(mapOf("d.json" to 7L), mapOf("d.json" to 9L))
        assertEquals(listOf("d.json"), p.conflicts)
    }
}
