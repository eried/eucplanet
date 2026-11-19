package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.store.OverlayPresetJson
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.ThemeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

private const val JSON_SUFFIX = ".json"

/** The [sub] folder of [root], made on demand; false when it cannot be. */
private fun ready(root: BackupFolder, sub: String): Boolean = root.ensureDirectory(sub)

/** The `.json` file names in [root]'s [sub], in the folder's own spelling. */
private fun jsonNames(root: BackupFolder, sub: String): List<String> =
    root.list(sub).orEmpty()
        .filter { !it.isDirectory && it.name.endsWith(JSON_SUFFIX, ignoreCase = true) }
        .map { it.name }

/**
 * Named custom themes as individual `.json` files in a backup folder's
 * `themes/`, so saving / loading is writing / reading one file. Android's
 * ThemeStore keeps them in the rider's backup folder; the iPhone in the
 * picked folder too, or in its own Documents while it has none. [root] is
 * that folder, or null when there is none to use.
 */
class FolderThemes(private val root: suspend () -> BackupFolder?) {
    private companion object {
        const val TAG = "ThemeStore"
    }

    /** True when a folder is there to keep saved themes in. */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) { root() != null }

    /**
     * Names of every saved custom theme, sorted case-insensitively. Names are
     * returned WITH the `.json` extension on purpose so the picker and the
     * stored `activeThemeName` always disambiguate custom from built-in (a
     * user theme called "Light" surfaces as "Light.json" and can no longer be
     * shadowed by the built-in "Light").
     */
    suspend fun listSaved(): List<String> = withContext(Dispatchers.IO) {
        val folder = root()?.takeIf { ready(it, BackupFolderSync.THEMES_SUBFOLDER) }
            ?: return@withContext emptyList()
        jsonNames(folder, BackupFolderSync.THEMES_SUBFOLDER).sortedBy { it.lowercase() }
    }

    /** Write [colors] as `<name>.json`. Trailing `.json` in `name` is tolerated
     *  so callers can pass either the bare base name or the listSaved form.
     *  An existing file is overwritten in place, not deleted and recreated:
     *  delete then create races Android's document provider, which dedupes
     *  the new file to `name (1).json`. */
    suspend fun save(name: String, colors: AppThemeColors): Boolean = withContext(Dispatchers.IO) {
        val base = if (name.endsWith(JSON_SUFFIX, ignoreCase = true)) name.dropLast(JSON_SUFFIX.length) else name
        val safe = BackupFolderSync.sanitizeBackupName(base) ?: return@withContext false
        val folder = root()?.takeIf { ready(it, BackupFolderSync.THEMES_SUBFOLDER) }
            ?: return@withContext false
        runCatching {
            val json = ThemeJson.colorsToJson(colors).apply { put("name", safe) }.toString(2)
            folder.write(folderPath(BackupFolderSync.THEMES_SUBFOLDER, "$safe$JSON_SUFFIX"), json.encodeToByteArray())
        }.getOrElse {
            Log.e(TAG, "Saving theme '$name' failed", it)
            false
        }
    }

    /** Load the named saved theme, or null if missing / unreadable. `name`
     *  may be passed with or without the `.json` suffix. */
    suspend fun load(name: String): AppThemeColors? = withContext(Dispatchers.IO) {
        val folder = root()?.takeIf { ready(it, BackupFolderSync.THEMES_SUBFOLDER) }
            ?: return@withContext null
        val withSuffix = if (name.endsWith(JSON_SUFFIX, ignoreCase = true)) name else "$name$JSON_SUFFIX"
        runCatching {
            val bytes = folder.read(folderPath(BackupFolderSync.THEMES_SUBFOLDER, withSuffix))
                ?: return@withContext null
            val json = JSONObject(bytes.decodeToString())
            // Pick the floor matching the saved flavor so a file that predates a
            // newly-added token fills the gap with a sensible same-flavor value.
            val floor = if (json.optBoolean("isLight", false)) BuiltInThemes.light.colors
            else BuiltInThemes.pureBlack.colors
            ThemeJson.colorsFromJson(json, floor)
        }.getOrElse {
            Log.e(TAG, "Loading theme '$name' failed", it)
            null
        }
    }
}

/**
 * The Overlay Studio's named presets as individual `.json` files in a
 * backup folder's `overlays/`: saving / loading a preset is writing /
 * reading one of those files. [root] as in [FolderThemes].
 */
class FolderPresets(private val root: suspend () -> BackupFolder?) {
    private companion object {
        const val TAG = "OverlayPresetStore"
    }

    /** True when a folder is there to keep named presets in. */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) { root() != null }

    /** Display names of every saved preset, sorted case-insensitively. */
    suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        val folder = root()?.takeIf { ready(it, BackupFolderSync.OVERLAYS_SUBFOLDER) }
            ?: return@withContext emptyList()
        jsonNames(folder, BackupFolderSync.OVERLAYS_SUBFOLDER)
            .map { it.dropLast(JSON_SUFFIX.length) }
            .sortedBy { it.lowercase() }
    }

    /** Write [preset] as `<name>.json`. If a file with that name already
     *  exists it is overwritten IN PLACE (same document), not deleted and
     *  recreated: delete + create races Android's document provider, which
     *  then dedupes the new file to `name (1).json` instead of overwriting --
     *  exactly the bug a rider hit when tapping "Overwrite".
     *
     *  To deliberately keep both copies, the caller passes a fresh unique name
     *  (e.g. "name (1)") -- which won't be found here, so a new file is made. */
    suspend fun save(name: String, preset: OverlayPreset): Boolean = withContext(Dispatchers.IO) {
        val safe = BackupFolderSync.sanitizeBackupName(name) ?: return@withContext false
        val folder = root()?.takeIf { ready(it, BackupFolderSync.OVERLAYS_SUBFOLDER) }
            ?: return@withContext false
        runCatching {
            val json = OverlayPresetJson.toJson(preset.copy(name = safe)).toString(2)
            folder.write(folderPath(BackupFolderSync.OVERLAYS_SUBFOLDER, "$safe$JSON_SUFFIX"), json.encodeToByteArray())
        }.getOrElse {
            Log.e(TAG, "Saving preset '$name' failed", it)
            false
        }
    }

    /** Load the named preset, or null if it is missing / unreadable. */
    suspend fun load(name: String): OverlayPreset? = withContext(Dispatchers.IO) {
        val folder = root()?.takeIf { ready(it, BackupFolderSync.OVERLAYS_SUBFOLDER) }
            ?: return@withContext null
        runCatching {
            val bytes = folder.read(folderPath(BackupFolderSync.OVERLAYS_SUBFOLDER, "$name$JSON_SUFFIX"))
                ?: return@withContext null
            OverlayPresetJson.fromJson(JSONObject(bytes.decodeToString()))
        }.getOrElse {
            Log.e(TAG, "Loading preset '$name' failed", it)
            null
        }
    }

    /** Delete the named preset file. */
    suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val folder = root()?.takeIf { ready(it, BackupFolderSync.OVERLAYS_SUBFOLDER) }
            ?: return@withContext false
        folder.delete(folderPath(BackupFolderSync.OVERLAYS_SUBFOLDER, "$name$JSON_SUFFIX"))
    }
}
