package com.eried.eucplanet.data.store

import android.content.Context
import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.ThemeJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for named custom themes. Mirrors [OverlayPresetStore] exactly:
 *
 *  - The 3 **built-in** themes ([BuiltInThemes]) are code constants, always
 *    available, read-only.
 *  - **Saved** themes are individual `.json` files in the rider's backup folder
 *    under a `themes/` subfolder — so saving / loading is writing / reading one
 *    file, which is why a backup folder must be set for saved themes to appear.
 *
 * The *active* theme (and any unsaved working draft) does NOT live here — settings
 * persist only the active theme's NAME; the resolved colors and in-memory drafts
 * are held by [com.eried.eucplanet.ui.theme.ThemeController] and re-derived from
 * the name on launch (a built-in from code, or a saved `.json` loaded from here).
 */
@Singleton
class ThemeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) {
    companion object {
        private const val TAG = "ThemeStore"
        private const val THEMES_SUBFOLDER = "themes"
        private const val SUFFIX = ".json"
    }

    /** Built-in theme names in combo order (Light, Dark, Pure Black). */
    fun builtInNames(): List<String> = BuiltInThemes.all.map { it.name }

    /** True when a backup folder is configured; required for saved themes. */
    suspend fun themesFolderAvailable(): Boolean = withContext(Dispatchers.IO) {
        syncManager.getSyncFolder(settingsRepository.get()) != null
    }

    /**
     * Names of every saved custom theme, sorted case-insensitively. Names are
     * returned WITH the `.json` extension on purpose so the picker and the
     * stored `activeThemeName` always disambiguate custom from built-in (a
     * user theme called "Light" surfaces as "Light.json" and can no longer be
     * shadowed by the built-in "Light").
     */
    suspend fun listSaved(): List<String> = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext emptyList()
        folder.listFiles()
            .mapNotNull { doc ->
                val n = doc.name ?: return@mapNotNull null
                if (doc.isFile && n.endsWith(SUFFIX, ignoreCase = true)) n else null
            }
            .sortedBy { it.lowercase() }
    }

    /** Write [colors] as `<name>.json`. Trailing `.json` in `name` is tolerated
     *  so callers can pass either the bare base name or the listSaved form. */
    suspend fun saveTheme(name: String, colors: AppThemeColors): Boolean = withContext(Dispatchers.IO) {
        val base = if (name.endsWith(SUFFIX, ignoreCase = true)) name.dropLast(SUFFIX.length) else name
        val safe = syncManager.sanitizeBackupName(base) ?: return@withContext false
        val folder = themesFolder() ?: return@withContext false
        val fileName = "$safe$SUFFIX"
        runCatching {
            folder.findFile(fileName)?.delete()
            val file = folder.createFile("application/json", fileName) ?: return@withContext false
            val json = ThemeJson.colorsToJson(colors).apply { put("name", safe) }.toString(2)
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        }.getOrElse {
            Log.e(TAG, "Saving theme '$name' failed", it)
            false
        }
    }

    /** Load the named saved theme, or null if missing / unreadable. `name`
     *  may be passed with or without the `.json` suffix. */
    suspend fun loadTheme(name: String): AppThemeColors? = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext null
        val withSuffix = if (name.endsWith(SUFFIX, ignoreCase = true)) name else "$name$SUFFIX"
        val file = folder.findFile(withSuffix) ?: return@withContext null
        runCatching {
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: return@withContext null
            val json = JSONObject(String(bytes, Charsets.UTF_8))
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

    /** Delete the named saved theme file. `name` may be passed with or
     *  without the `.json` suffix. */
    suspend fun deleteTheme(name: String): Boolean = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext false
        val withSuffix = if (name.endsWith(SUFFIX, ignoreCase = true)) name else "$name$SUFFIX"
        folder.findFile(withSuffix)?.delete() ?: false
    }

    /** The `themes/` subfolder of the backup folder, created on demand. */
    private suspend fun themesFolder() = withContext(Dispatchers.IO) {
        val root = syncManager.getSyncFolder(settingsRepository.get()) ?: return@withContext null
        root.findFile(THEMES_SUBFOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(THEMES_SUBFOLDER)
    }
}
