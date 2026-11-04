package com.eried.eucplanet.nav.offline

import com.eried.eucplanet.nav.offline.map.PmTiles
import java.io.File

/**
 * A downloaded map square is kept only when it reads whole: every directory
 * and a sample of the tiles unzip, and nothing points past the end.
 */
object MapCheck {

    /** Why [file] can't be used, or null when it reads whole. */
    fun problem(file: File): String? = try {
        if (file.length() == 0L) "empty" else {
            PmTiles(file).use { it.verify() }
            null
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
