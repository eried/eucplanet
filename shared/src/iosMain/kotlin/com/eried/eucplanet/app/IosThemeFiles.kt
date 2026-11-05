package com.eried.eucplanet.app

import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.data.sync.JsonFolder
import com.eried.eucplanet.ui.theme.AppThemeColors
import com.eried.eucplanet.ui.theme.BuiltInThemes
import com.eried.eucplanet.ui.theme.ThemeFiles
import com.eried.eucplanet.ui.theme.ThemeJson
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * Saved themes on iOS: one `<name>.json` each in Documents/themes, the same
 * JSON Android writes to its backup folder's themes/, and the folder the
 * Dropbox sync mirrors with /themes.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosThemeFiles(documents: String) : ThemeFiles, JsonFolder {
    private val dir = "$documents/themes"
    private val fm = NSFileManager.defaultManager

    private fun file(name: String) =
        "$dir/" + if (name.endsWith(SUFFIX, ignoreCase = true)) name else "$name$SUFFIX"

    override suspend fun themesFolderAvailable(): Boolean =
        fm.fileExistsAtPath(dir) || fm.createDirectoryAtPath(dir, true, null, null)

    override suspend fun listSaved(): List<String> =
        (fm.contentsOfDirectoryAtPath(dir, null) ?: emptyList<Any>())
            .mapNotNull { it as? String }
            .filter { it.endsWith(SUFFIX, ignoreCase = true) }
            .sortedBy { it.lowercase() }

    override suspend fun saveTheme(name: String, colors: AppThemeColors): Boolean {
        val base = if (name.endsWith(SUFFIX, ignoreCase = true)) name.dropLast(SUFFIX.length) else name
        if (base.isBlank() || !themesFolderAvailable()) return false
        val json = ThemeJson.colorsToJson(colors).apply { put("name", base) }.toString(2)
        @Suppress("CAST_NEVER_SUCCEEDS")
        return (NSString.create(string = json)).writeToFile(file(base), true, NSUTF8StringEncoding, null)
    }

    override suspend fun loadTheme(name: String): AppThemeColors? = runCatching {
        val text = NSString.stringWithContentsOfFile(file(name), NSUTF8StringEncoding, null) ?: return null
        val json = JSONObject(text)
        // Same floor rule as Android's ThemeStore.
        val floor = if (json.optBoolean("isLight", false)) BuiltInThemes.light.colors
        else BuiltInThemes.pureBlack.colors
        ThemeJson.colorsFromJson(json, floor)
    }.getOrElse {
        Log.e("ThemeFiles", "Loading theme '$name' failed")
        null
    }

    override suspend fun jsonFiles(): Map<String, ByteArray>? {
        if (!themesFolderAvailable()) return null
        return listSaved().mapNotNull { name ->
            NSData.dataWithContentsOfFile("$dir/$name")?.let { name to it.toByteArray() }
        }.toMap()
    }

    override suspend fun write(name: String, bytes: ByteArray): Boolean {
        if (!themesFolderAvailable()) return false
        return bytes.toNSData().writeToFile("$dir/$name", true)
    }

    private companion object {
        const val SUFFIX = ".json"
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }
