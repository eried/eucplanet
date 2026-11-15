package com.eried.eucplanet.nav.offline

import android.content.Context
import java.io.File

/**
 * BRouter's profiles on Android: copied from the APK into the tile folder,
 * once per run, so an update ships a new wheel profile. The tag table must
 * match the tiles, so the one downloaded with them wins and the APK's is only
 * the fallback.
 */
class AndroidBrouterProfiles(
    private val context: Context,
    private val store: OfflineTileStore,
) : BrouterProfiles {

    override fun folder(): String {
        val profiles = File(store.dir, "profiles").apply { mkdirs() }
        copyAssets(profiles)
        return profiles.path
    }

    private fun copyAssets(profiles: File) {
        if (!profilesCopied) {
            for (name in ASSETS) {
                context.assets.open("brouter/$name").use { input ->
                    File(profiles, name).outputStream().use { input.copyTo(it) }
                }
            }
            profilesCopied = true
        }
        val downloaded = store.lookupsFile()
        val used = File(profiles, "lookups.dat")
        if (downloaded.isFile && downloaded.lastModified() != used.lastModified()) {
            downloaded.copyTo(used, overwrite = true)
            used.setLastModified(downloaded.lastModified())
        }
    }

    private companion object {
        /** Profiles and the fallback tag table shipped in the APK. */
        val ASSETS = listOf("wheel.brf", "hiking-mountain.brf", "lookups.dat")

        @Volatile var profilesCopied = false
    }
}
