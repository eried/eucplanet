package com.eried.eucplanet.data.store

import android.content.Context
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.sync.FolderThemes
import com.eried.eucplanet.data.sync.SyncManager
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.ThemeFiles

/**
 * Persistence for named custom themes. Mirrors [OverlayPresetStore] exactly:
 *
 *  - The 3 **built-in** themes ([BuiltInThemes]) are code constants, always
 *    available, read-only.
 *  - **Saved** themes are individual `.json` files in the rider's backup folder
 *    under a `themes/` subfolder, so saving / loading is writing / reading one
 *    file, which is why a backup folder must be set for saved themes to appear.
 *    The files are the shared [FolderThemes]', the iPhone's too.
 *
 * The *active* theme (and any unsaved working draft) does NOT live here, settings
 * persist only the active theme's NAME; the resolved colors and in-memory drafts
 * are held by [com.eried.eucplanet.ui.theme.ThemeController] and re-derived from
 * the name on launch (a built-in from code, or a saved `.json` loaded from here).
 */
class ThemeStore(
    @Suppress("unused") private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) : ThemeFiles {

    private val files = FolderThemes { syncManager.getSyncFolder(settingsRepository.get()) }

    /** Built-in theme names in combo order (Light, Dark, Pure Black). */
    override fun builtInNames(): List<String> = BuiltInThemes.all.map { it.name }

    /** True when a backup folder is configured; required for saved themes. */
    override suspend fun themesFolderAvailable(): Boolean = files.available()

    /** See [FolderThemes.listSaved]. */
    override suspend fun listSaved(): List<String> = files.listSaved()

    /** See [FolderThemes.save]. */
    override suspend fun saveTheme(name: String, colors: AppThemeColors): Boolean = files.save(name, colors)

    /** See [FolderThemes.load]. */
    override suspend fun loadTheme(name: String): AppThemeColors? = files.load(name)
}
