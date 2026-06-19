package com.eried.eucplanet.data.store

import android.content.Context
import android.util.Log
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.sync.SyncManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for Overlay Studio configurations.
 *
 * Two tiers:
 *  - The **working draft** is the layout the rider is currently editing. It is
 *    a single file in app-private storage so it survives the studio screen
 *    being closed and reopened, but it is intentionally throwaway, there is
 *    only ever one.
 *  - **Named presets** are individual `.json` files in the rider's configured
 *    backup folder, under an `overlays/` subfolder. Saving / loading a preset
 *    is literally writing / reading one of those files, which is why the studio
 *    asks the rider to pick a backup folder before the save/load actions work.
 */
@Singleton
class OverlayPresetStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) {
    companion object {
        private const val TAG = "OverlayPresetStore"
        private const val DRAFT_FILE = "overlay_studio_draft.json"
        private const val PRESET_SUBFOLDER = "overlays"
        private const val PRESET_SUFFIX = ".json"
        /** Bundled starter presets ship here; the rider can load but not edit them. */
        private const val BUNDLED_DIR = "overlay_presets"
        /** First-launch layout the rider sees before they save anything, kept
         *  outside [BUNDLED_DIR] so it doesn't pollute the Load Starter list. */
        private const val DEFAULT_ASSET = "overlay_default.json"
    }

    private val draftFile: File get() = File(context.filesDir, DRAFT_FILE)

    /**
     * The throwaway working layout. On first launch (no draft yet) we hand the
     * rider the bundled default layout from assets so the studio opens with a
     * useful starting composition rather than an empty canvas; once they edit
     * and the draft is written, this branch is never hit again on this device.
     */
    suspend fun loadDraft(): OverlayPreset = withContext(Dispatchers.IO) {
        if (!draftFile.exists()) return@withContext loadBundledDefault() ?: OverlayPreset()
        runCatching {
            OverlayPresetJson.fromJson(JSONObject(draftFile.readText()))
        }.getOrElse {
            Log.w(TAG, "Draft unreadable, starting fresh", it)
            loadBundledDefault() ?: OverlayPreset()
        }
    }

    /** First-launch layout from assets; null if missing or unreadable. */
    private fun loadBundledDefault(): OverlayPreset? = runCatching {
        context.assets.open(DEFAULT_ASSET).use { stream ->
            OverlayPresetJson.fromJson(JSONObject(stream.readBytes().decodeToString()))
        }
    }.getOrElse {
        Log.w(TAG, "Bundled default preset unreadable", it)
        null
    }

    /** Persist the current working layout. Best-effort; failures are logged. */
    suspend fun saveDraft(preset: OverlayPreset) = withContext(Dispatchers.IO) {
        runCatching {
            draftFile.writeText(OverlayPresetJson.toJson(preset).toString())
        }.onFailure { Log.w(TAG, "Could not save draft", it) }
        Unit
    }

    /** True when a backup folder is configured and writable, required for
     *  named presets. The studio warns the rider when this is false. */
    suspend fun presetFolderAvailable(): Boolean = withContext(Dispatchers.IO) {
        syncManager.getSyncFolder(settingsRepository.get()) != null
    }

    /** Display names of every saved preset, sorted case-insensitively. */
    suspend fun listPresets(): List<String> = withContext(Dispatchers.IO) {
        val folder = overlaysFolder() ?: return@withContext emptyList()
        folder.listFiles()
            .mapNotNull { doc ->
                val n = doc.name ?: return@mapNotNull null
                if (doc.isFile && n.endsWith(PRESET_SUFFIX, ignoreCase = true)) {
                    n.dropLast(PRESET_SUFFIX.length)
                } else null
            }
            .sortedBy { it.lowercase() }
    }

    /** Write [preset] as `<name>.json`. If a file with that name already
     *  exists it is overwritten IN PLACE (same document), not deleted and
     *  recreated: `delete()` + `createFile()` races the SAF provider, which
     *  then dedupes the new file to `name (1).json` instead of overwriting --
     *  exactly the bug a rider hit when tapping "Overwrite". The "wt" mode
     *  truncates so a shorter preset doesn't leave stale trailing bytes.
     *
     *  To deliberately keep both copies, the caller passes a fresh unique name
     *  (e.g. "name (1)") -- which won't be found here, so a new file is made. */
    suspend fun savePreset(name: String, preset: OverlayPreset): Boolean =
        withContext(Dispatchers.IO) {
            val safe = syncManager.sanitizeBackupName(name) ?: return@withContext false
            val folder = overlaysFolder() ?: return@withContext false
            val fileName = "$safe$PRESET_SUFFIX"
            runCatching {
                val json = OverlayPresetJson.toJson(preset.copy(name = safe)).toString(2)
                val target = (folder.findFile(fileName)
                    ?: folder.createFile("application/json", fileName))?.uri
                    ?: return@withContext false
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: return@withContext false
                true
            }.getOrElse {
                Log.e(TAG, "Saving preset '$name' failed", it)
                false
            }
        }

    /** Load the named preset, or null if it is missing / unreadable. */
    suspend fun loadPreset(name: String): OverlayPreset? = withContext(Dispatchers.IO) {
        val folder = overlaysFolder() ?: return@withContext null
        val file = folder.findFile("$name$PRESET_SUFFIX") ?: return@withContext null
        runCatching {
            val bytes = context.contentResolver.openInputStream(file.uri)
                ?.use { it.readBytes() } ?: return@withContext null
            OverlayPresetJson.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
        }.getOrElse {
            Log.e(TAG, "Loading preset '$name' failed", it)
            null
        }
    }

    /**
     * Display names of the presets bundled with the app (read-only starters in
     * `assets/overlay_presets/`). Available even with no backup folder set.
     */
    suspend fun listBundledPresets(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.list(BUNDLED_DIR)
                ?.filter { it.endsWith(PRESET_SUFFIX, ignoreCase = true) }
                ?.map { it.dropLast(PRESET_SUFFIX.length) }
                ?.sortedBy { it.lowercase() }
                .orEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not list bundled presets", it)
            emptyList()
        }
    }

    /** Load a bundled starter preset from app assets. */
    suspend fun loadBundledPreset(name: String): OverlayPreset? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("$BUNDLED_DIR/$name$PRESET_SUFFIX").use { stream ->
                OverlayPresetJson.fromJson(JSONObject(stream.readBytes().decodeToString()))
            }
        }.getOrElse {
            Log.e(TAG, "Loading bundled preset '$name' failed", it)
            null
        }
    }

    /** Delete the named preset file. */
    suspend fun deletePreset(name: String): Boolean = withContext(Dispatchers.IO) {
        val folder = overlaysFolder() ?: return@withContext false
        folder.findFile("$name$PRESET_SUFFIX")?.delete() ?: false
    }

    /** The `overlays/` subfolder of the backup folder, created on demand. */
    private suspend fun overlaysFolder() = withContext(Dispatchers.IO) {
        val root = syncManager.getSyncFolder(settingsRepository.get())
            ?: return@withContext null
        root.findFile(PRESET_SUBFOLDER)?.takeIf { it.isDirectory }
            ?: root.createDirectory(PRESET_SUBFOLDER)
    }
}
