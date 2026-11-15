package com.eried.eucplanet.nav.offline

import btools.util.joinPath

/**
 * The routing tiles on this phone, in [dir]. A tile counts only once it is
 * whole: downloads write `<name>.rd5.part` and rename on success, so a half
 * file never reaches BRouter. Android keeps [dir] in the app's own storage
 * (not backed up, removed with the app); the iPhone in Application Support,
 * excluded from backup.
 */
class OfflineTileStore(val dir: String, val files: OfflineFiles) {

    fun finalFile(name: String): String = joinPath(dir, "$name.rd5")
    fun partFile(name: String): String = joinPath(dir, "$name.rd5.part")

    /** The square's offline map, and its download in progress. */
    fun mapFile(name: String): String = joinPath(dir, "$name.pmtiles")
    fun mapPartFile(name: String): String = joinPath(dir, "$name.pmtiles.part")

    /** BRouter's tag table, which must match the tiles' build. */
    fun lookupsFile(): String = joinPath(dir, "lookups.dat")

    fun has(name: String): Boolean = finalFile(name).let { files.isFile(it) && files.length(it) > 0 }

    /** True when every tile in [tiles] is here, and there is at least one. */
    fun covers(tiles: Set<String>): Boolean = tiles.isNotEmpty() && tiles.all(::has)

    /** Whole squares on disk, name to bytes of routing and map together. */
    fun downloaded(): Map<String, Long> =
        files.list(dir)
            .filter { it.endsWith(".rd5") }
            .map { it to files.length(joinPath(dir, it)) }
            .filter { (_, bytes) -> bytes > 0 }
            .associate { (file, bytes) -> file.removeSuffix(".rd5") to bytes }
            .filterKeys(RoutingTiles::isTileName)
            .mapValues { (name, bytes) -> bytes + files.length(mapFile(name)) }

    fun usedBytes(): Long = downloaded().values.sum()

    /** Bytes free where the tiles go, 0 when the phone cannot say. */
    fun freeBytes(): Long = runCatching { files.freeBytes(dir) }.getOrDefault(0L)

    fun delete(name: String) {
        files.delete(finalFile(name))
        files.delete(partFile(name))
        files.delete(mapFile(name))
        files.delete(mapPartFile(name))
    }
}
