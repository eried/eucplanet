package com.eried.eucplanet.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The trip-detail tile registry stays in sync with its key list.
 *
 * The screen has a runtime drift guard, but it is debug-only, which is how
 * 0.16.3 shipped with energy and consumption in allTiles and not in
 * TILE_KEYS_DEFAULT: release users saw nothing, and every tester on a debug
 * build crashed the moment they opened a trip (a real crash log from a
 * French Motorola landed on 2026-08-23). A registry rule that only fires
 * after the mistake reaches a device is a test that runs too late - this one
 * runs in CI.
 */
class TripTileRegistryTest {

    private val src = File("src/main/java/com/eried/eucplanet/ui/recording/TripDetailScreen.kt").readText()

    /** The `listOf(...)` / `setOf(...)` block that follows [anchor], to its matching close. */
    private fun listBlock(anchor: String): String {
        val i = src.indexOf(anchor)
        check(i >= 0) { "$anchor not found" }
        val j = listOf(src.indexOf("listOf(", i), src.indexOf("setOf(", i))
            .filter { it >= 0 }.min()
        var depth = 0
        var k = j
        while (true) {
            when (src[k]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) break }
            }
            k++
        }
        return src.substring(i, k)
    }

    private fun keysOf(block: String) = Regex("\"([A-Za-z]+)\"").findAll(block).map { it.groupValues[1] }.toList()

    @Test fun `every tile in allTiles is in TILE_KEYS_DEFAULT, in order`() {
        // allTiles entries look like  "key" to { ... }; keys are the quoted
        // strings immediately followed by ` to`.
        val i = src.indexOf("val allTiles:")
        check(i >= 0)
        val body = src.substring(i, src.indexOf("val effectiveTileOrder", i))
        val tiles = Regex("\"([A-Za-z]+)\"\\s+to\\s*\\{").findAll(body).map { it.groupValues[1] }.toList()
        val defaults = keysOf(listBlock("private val TILE_KEYS_DEFAULT"))
        assertTrue("no tiles parsed - the test's anchor broke", tiles.size >= 10)
        assertEquals(defaults, tiles)
    }

    @Test fun `the opt-in extras all exist as real tiles`() {
        val extras = keysOf(listBlock("private val EXTRA_TILE_KEYS"))
        val defaults = keysOf(listBlock("private val TILE_KEYS_DEFAULT"))
        for (k in extras) assertTrue("$k is opt-in but not a tile", k in defaults)
    }
}
