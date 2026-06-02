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
 * The *active* theme (including the unsaved working draft) does NOT live here —
 * it is a JSON snapshot in AppSettings/DataStore so it works with no folder and
 * rides along with the settings backup.
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

    /** Names of every saved custom theme, sorted case-insensitively. */
    suspend fun listSaved(): List<String> = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext emptyList()
        folder.listFiles()
            .mapNotNull { doc ->
                val n = doc.name ?: return@mapNotNull null
                if (doc.isFile && n.endsWith(SUFFIX, ignoreCase = true)) n.dropLast(SUFFIX.length) else null
            }
            .sortedBy { it.lowercase() }
    }

    /** Write [colors] as `<name>.json`, overwriting any file with that name. */
    suspend fun saveTheme(name: String, colors: AppThemeColors): Boolean = withContext(Dispatchers.IO) {
        val safe = syncManager.sanitizeBackupName(name) ?: return@withContext false
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

    /** Load the named saved theme, or null if missing / unreadable. */
    suspend fun loadTheme(name: String): AppThemeColors? = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext null
        val file = folder.findFile("$name$SUFFIX") ?: return@withContext null
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

    /** Delete the named saved theme file. */
    suspend fun deleteTheme(name: String): Boolean = withContext(Dispatchers.IO) {
        val folder = themesFolder() ?: return@withContext false
        folder.findFile("$name$SUFFIX")?.delete() ?: false
    }

    /** The `themes/` subfolder of the backup folder, created on demand. */
    private suspend fun themesFolder() = withContext(Dispatchers.IO) {
        val root = syncManager.getSyncFolder(settingsRepository.get()) ?: return@withContext null
        root.findFile(THEMES_SUBFOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(THEMES_SUBFOLDER)
    }
}
