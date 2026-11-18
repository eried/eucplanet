package com.eried.eucplanet.nav.offline

import android.content.Context
import java.io.File

/**
 * The routing tiles on this phone, in [dir]. A tile counts only once it is
 * whole: downloads write `<name>.rd5.part` and rename on success, so a half
 * file never reaches BRouter.
 */
class OfflineTileStore(val dir: File) {

    fun finalFile(name: String): File = File(dir, "$name.rd5")
    fun partFile(name: String): File = File(dir, "$name.rd5.part")

    /** The square's offline map, and its download in progress. */
    fun mapFile(name: String): File = File(dir, "$name.pmtiles")
    fun mapPartFile(name: String): File = File(dir, "$name.pmtiles.part")

    /** BRouter's tag table, which must match the tiles' build. */
    fun lookupsFile(): File = File(dir, "lookups.dat")

    fun has(name: String): Boolean = finalFile(name).let { it.isFile && it.length() > 0 }

    /** True when every tile in [tiles] is here, and there is at least one. */
    fun covers(tiles: Set<String>): Boolean = tiles.isNotEmpty() && tiles.all(::has)

    /** Whole squares on disk, name to bytes of routing and map together. */
    fun downloaded(): Map<String, Long> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".rd5") && it.length() > 0 }
            .associate { it.name.removeSuffix(".rd5") to it.length() }
            .filterKeys(RoutingTiles::isTileName)
            .mapValues { (name, bytes) -> bytes + mapFile(name).length() }

    fun usedBytes(): Long = downloaded().values.sum()

    fun delete(name: String) {
        finalFile(name).delete()
        partFile(name).delete()
        mapFile(name).delete()
        mapPartFile(name).delete()
    }

    companion object {
        /** App storage, not backed up and removed with the app; internal when
         *  there is no external storage. */
        fun default(ctx: Context): OfflineTileStore {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            return OfflineTileStore(File(base, "offline/routing").apply { mkdirs() })
        }
    }
}
