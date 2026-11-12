package com.eried.eucplanet.data.sync

/**
 * How many trips the phone and the backup folder both hold with different
 * content. Sizes stand in for content, as everywhere in the folder sync.
 * Names are matched without case, because shared storage ignores it. A trip
 * on one side only is not a conflict, it is just a copy still to make.
 */
internal fun countFolderConflicts(localSizes: Map<String, Long>, folderSizes: Map<String, Long>): Int {
    val folderByLower = folderSizes.mapKeys { it.key.lowercase() }
    return localSizes.count { (name, size) ->
        val inFolder = folderByLower[name.lowercase()] ?: return@count false
        inFolder != size
    }
}
