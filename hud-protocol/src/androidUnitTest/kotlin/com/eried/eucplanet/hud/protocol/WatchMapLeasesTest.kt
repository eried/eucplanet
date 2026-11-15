package com.eried.eucplanet.hud.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString

class WatchMapLeasesTest {

    private fun presence(
        viewerId: String,
        viewerEpoch: Long,
        sequence: Long,
        foreground: Boolean = true,
        mapVisible: Boolean = true,
    ) = WatchMapPresence(
        viewerId = viewerId,
        viewerEpoch = viewerEpoch,
        sequence = sequence,
        foreground = foreground,
        mapVisible = mapVisible,
        missingRoute = null,
        missingTiles = emptyList(),
    )

    @Test
    fun twoNodesRemainIndependentWhenOneHides() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 1L, 0L), 100L))
        assertTrue(leases.accept("node-b", presence("viewer-b", 1L, 0L), 100L))

        assertTrue(
            leases.accept(
                "node-a",
                presence("viewer-a", 1L, 1L, foreground = false),
                200L,
            ),
        )
        assertTrue(leases.hasVisibleMap(200L))

        assertTrue(
            leases.accept(
                "node-b",
                presence("viewer-b", 1L, 1L, mapVisible = false),
                300L,
            ),
        )
        assertFalse(leases.hasVisibleMap(300L))
    }

    @Test
    fun lostHideExpiresAndANewSequenceCanReopenTheLease() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 2L, 4L), 1_000L))
        assertTrue(leases.hasVisibleMap(1_000L + WatchMapProtocol.LEASE_MS - 1L))
        assertFalse(leases.hasVisibleMap(1_000L + WatchMapProtocol.LEASE_MS))

        assertTrue(
            leases.accept(
                "node-a",
                presence("viewer-a", 2L, 5L),
                1_000L + WatchMapProtocol.LEASE_MS + 1L,
            ),
        )
        assertTrue(leases.hasVisibleMap(1_000L + WatchMapProtocol.LEASE_MS + 1L))
    }

    @Test
    fun retiredViewerLowerEpochAndOldSequenceCannotRestoreDemand() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 5L, 10L), 100L))
        assertTrue(
            leases.accept(
                "node-a",
                presence("viewer-a", 5L, 11L, foreground = false),
                200L,
            ),
        )
        assertFalse(leases.hasVisibleMap(200L))

        assertFalse(leases.accept("node-a", presence("viewer-a", 5L, 11L), 300L))
        assertTrue(leases.accept("node-a", presence("viewer-b", 4L, 0L), 300L))
        assertTrue(
            leases.accept(
                "node-a",
                presence("viewer-b", 4L, 1L, foreground = false),
                400L,
            ),
        )
        assertFalse(leases.accept("node-a", presence("viewer-a", 6L, 12L), 500L))
        assertFalse(leases.hasVisibleMap(500L))
    }

    @Test
    fun activeViewerBlocksAnotherViewerUntilLeaseExpires() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 50L, 10L), 1_000L))
        assertFalse(leases.accept("node-a", presence("viewer-b", 1L, 0L), 6_999L))
        assertTrue(leases.accept("node-a", presence("viewer-b", 1L, 0L), 7_000L))
        assertFalse(leases.accept("node-a", presence("viewer-a", 1_000L, 11L), 7_000L))
    }

    @Test
    fun invisibleForegroundViewerStillBlocksAnotherViewerUntilLeaseExpires() {
        val leases = WatchMapLeases()
        assertTrue(
            leases.accept(
                "node-a",
                presence(
                    "viewer-a",
                    50L,
                    10L,
                    foreground = true,
                    mapVisible = false,
                ),
                1_000L,
            ),
        )
        assertFalse(leases.accept("node-a", presence("viewer-b", 1L, 0L), 6_999L))
        assertTrue(leases.accept("node-a", presence("viewer-b", 1L, 0L), 7_000L))
    }

    @Test
    fun hiddenViewerCanBeReplacedImmediately() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 50L, 10L), 1_000L))
        assertTrue(
            leases.accept(
                "node-a",
                presence("viewer-a", 50L, 11L, foreground = false),
                1_001L,
            ),
        )
        assertTrue(leases.accept("node-a", presence("viewer-b", 1L, 0L), 1_002L))
        assertFalse(leases.accept("node-a", presence("viewer-a", 1_000L, 12L), 1_003L))
    }

    @Test
    fun restoreKeepsHistoryWithoutRestoringActiveLease() {
        val leases = WatchMapLeases()
        assertTrue(leases.accept("node-a", presence("viewer-a", 50L, 10L), 100L))
        val snapshot = leases.snapshot()

        val restored = WatchMapLeases()
        restored.restore(snapshot)
        assertFalse(restored.hasVisibleMap(100L))
        assertTrue(restored.accept("node-a", presence("viewer-b", 1L, 0L), 101L))
        assertFalse(restored.accept("node-a", presence("viewer-a", 50L, 10L), 102L))
    }
    @Test
    fun restoreFiltersOldViewerEpochsAndRoundTripsDuplicateCurrentSequence() {
        val snapshot = WatchMapLeasesSnapshot(
            nodes = listOf(
                WatchMapLeaseNodeSnapshot(
                    nodeId = "node-a",
                    currentViewerId = "viewer-a",
                    maxViewerEpoch = 50L,
                    highWater = listOf(
                        WatchMapLeaseHighWater("viewer-a", 49L, 900L),
                        WatchMapLeaseHighWater("viewer-a", 50L, 10L),
                        WatchMapLeaseHighWater("viewer-a", 50L, 12L),
                        WatchMapLeaseHighWater("viewer-b", 40L, 100L),
                    ),
                    retiredViewerIds = listOf("retired"),
                ),
                WatchMapLeaseNodeSnapshot(
                    nodeId = "",
                    currentViewerId = "invalid",
                    maxViewerEpoch = 0L,
                    highWater = emptyList(),
                    retiredViewerIds = emptyList(),
                ),
            ),
        )
        val json = WatchMapProtocol.json.encodeToString(
            WatchMapLeasesSnapshot.serializer(),
            snapshot,
        )
        val restored = WatchMapLeases().apply {
            restore(
                WatchMapProtocol.json.decodeFromString(
                    WatchMapLeasesSnapshot.serializer(),
                    json,
                ),
            )
        }

        assertEquals(1, restored.snapshot().nodes.size)
        assertFalse(restored.hasVisibleMap(100L))
        assertEquals(
            listOf(WatchMapLeaseHighWater("viewer-a", 50L, 12L)),
            restored.snapshot().nodes.single().highWater,
        )
        assertFalse(restored.accept("node-a", presence("viewer-a", 50L, 12L), 101L))
        assertTrue(restored.accept("node-a", presence("viewer-a", 50L, 13L), 102L))
        assertFalse(restored.accept("node-a", presence("viewer-a", 49L, 901L), 103L))
    }

    @Test
    fun historyRetainsOnlyCurrentViewerEpoch() {
        val leases = WatchMapLeases()
        repeat(1_000) { epoch ->
            assertTrue(
                leases.accept(
                    "node-a",
                    presence("viewer-a", epoch.toLong(), 0L),
                    epoch.toLong(),
                ),
            )
            assertTrue(
                leases.accept(
                    "node-a",
                    presence("viewer-a", epoch.toLong(), 1L, foreground = false),
                    epoch.toLong(),
                ),
            )
        }

        val snapshot = leases.snapshot()
        assertTrue(snapshot.nodes.single().highWater.single().viewerEpoch == 999L)
        val json = WatchMapProtocol.json.encodeToString(
            WatchMapLeasesSnapshot.serializer(),
            snapshot,
        )
        val decoded = WatchMapProtocol.json.decodeFromString(
            WatchMapLeasesSnapshot.serializer(),
            json,
        )

        val restored = WatchMapLeases()
        restored.restore(decoded)
        assertFalse(restored.accept("node-a", presence("viewer-a", 999L, 1L), 2_000L))
        assertTrue(restored.accept("node-a", presence("viewer-a", 999L, 2L), 2_000L))
        assertFalse(restored.accept("node-a", presence("viewer-a", 998L, 2L), 2_001L))
    }

    @Test
    fun clearAndRestoreKeepHistoryButNeverRestoreAnActiveLease() {
        val original = WatchMapLeases()
        assertTrue(original.accept("node-a", presence("viewer-a", 3L, 7L), 100L))
        val snapshot = original.snapshot()
        original.clear()
        assertFalse(original.hasVisibleMap(100L))

        val restored = WatchMapLeases()
        restored.restore(snapshot)
        assertFalse(restored.hasVisibleMap(100L))
        assertFalse(restored.accept("node-a", presence("viewer-a", 3L, 7L), 200L))
        assertTrue(restored.accept("node-a", presence("viewer-a", 3L, 8L), 200L))
        assertTrue(restored.hasVisibleMap(200L))
    }
}
