package com.eried.eucplanet.nav.offline

import btools.mapaccess.PhysicalFile
import btools.util.BFiles

/**
 * BRouter's own check of a routing tile: it reads every index and data
 * checksum, so a file of the right length with damaged bytes is caught
 * here instead of failing a route later. About 0.3 s for 65 MB.
 */
object TileCheck {

    /** Why the tile at [path] can't be used, or null when it reads whole. */
    fun problem(path: String): String? = try {
        if (BFiles.length(path) == 0L) "empty" else {
            PhysicalFile.checkFileIntegrity(path)
            null
        }
    } catch (e: Exception) {
        e.message ?: e::class.simpleName
    }
}
