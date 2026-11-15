package com.eried.eucplanet.nav.offline

import btools.util.BFiles
import com.eried.eucplanet.nav.offline.map.PmTiles
import com.eried.eucplanet.nav.offline.map.openPath

/**
 * A downloaded map square is kept only when it reads whole: every directory
 * and a sample of the tiles unzip, and nothing points past the end.
 */
object MapCheck {

    /** Why [path] can't be used, or null when it reads whole. */
    fun problem(path: String): String? = try {
        if (BFiles.length(path) == 0L) "empty" else {
            PmTiles.openPath(path).use { it.verify() }
            null
        }
    } catch (e: Exception) {
        e.message ?: e::class.simpleName
    }
}
