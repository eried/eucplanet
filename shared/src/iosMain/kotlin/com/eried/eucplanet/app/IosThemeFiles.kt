package com.eried.eucplanet.app

import com.eried.eucplanet.data.sync.BackupFolder
import com.eried.eucplanet.data.sync.FolderThemes
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.ThemeFiles

/**
 * Saved themes on iOS: one `<name>.json` each in the backup folder's
 * themes/, the same files Android's ThemeStore writes there (the shared
 * [FolderThemes]), or in Documents/themes while no folder is set. [root] is
 * that folder ([IosFolderSync.jsonRoot]); Dropbox mirrors its themes/ with
 * /themes.
 */
internal class IosThemeFiles(root: suspend () -> BackupFolder) : ThemeFiles {
    private val files = FolderThemes(root)

    // Documents is always there, so saved themes always are.
    override suspend fun themesFolderAvailable(): Boolean = files.available()
    override suspend fun listSaved(): List<String> = files.listSaved()
    override suspend fun saveTheme(name: String, colors: AppThemeColors): Boolean = files.save(name, colors)
    override suspend fun loadTheme(name: String): AppThemeColors? = files.load(name)
}
