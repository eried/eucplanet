package com.eried.eucplanet.data.store

import android.content.Context
import android.util.Log
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.sync.FolderPresets
import com.eried.eucplanet.data.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
class OverlayPresetStore(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncManager: SyncManager
) : OverlayPresets {
    companion object {
        private const val TAG = "OverlayPresetStore"
        private const val DRAFT_FILE = "overlay_studio_draft.json"
        private const val PRESET_SUFFIX = ".json"
        /** Bundled starter presets ship here; the rider can load but not edit them. */
        private const val BUNDLED_DIR = "overlay_presets"
        /** First-launch layout the rider sees before they save anything, kept
         *  outside [BUNDLED_DIR] so it doesn't pollute the Load Starter list. */
        private const val DEFAULT_ASSET = "overlay_default.json"
    }

    private val draftFile: File get() = File(context.filesDir, DRAFT_FILE)

    /** The named presets in the backup folder's overlays/, the shared [FolderPresets] (the iPhone's too). */
    private val presets = FolderPresets { syncManager.getSyncFolder(settingsRepository.get()) }

    /**
     * The throwaway working layout. On first launch (no draft yet) we hand the
     * rider the bundled default layout from assets so the studio opens with a
     * useful starting composition rather than an empty canvas; once they edit
     * and the draft is written, this branch is never hit again on this device.
     */
    override suspend fun loadDraft(): OverlayPreset = withContext(Dispatchers.IO) {
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
    override suspend fun saveDraft(preset: OverlayPreset) = withContext(Dispatchers.IO) {
        runCatching {
            draftFile.writeText(OverlayPresetJson.toJson(preset).toString())
        }.onFailure { Log.w(TAG, "Could not save draft", it) }
        Unit
    }

    /** True when a backup folder is configured and writable, required for
     *  named presets. The studio warns the rider when this is false. */
    override suspend fun presetFolderAvailable(): Boolean = presets.available()

    /** Display names of every saved preset, sorted case-insensitively. */
    override suspend fun listPresets(): List<String> = presets.list()

    /** Write [preset] as `<name>.json`, overwriting one of that name in place. See [FolderPresets.save]. */
    override suspend fun savePreset(name: String, preset: OverlayPreset): Boolean = presets.save(name, preset)

    /** Load the named preset, or null if it is missing / unreadable. */
    override suspend fun loadPreset(name: String): OverlayPreset? = presets.load(name)

    /**
     * Display names of the presets bundled with the app (read-only starters in
     * `assets/overlay_presets/`). Available even with no backup folder set.
     */
    override suspend fun listBundledPresets(): List<String> = withContext(Dispatchers.IO) {
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
    override suspend fun loadBundledPreset(name: String): OverlayPreset? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("$BUNDLED_DIR/$name$PRESET_SUFFIX").use { stream ->
                OverlayPresetJson.fromJson(JSONObject(stream.readBytes().decodeToString()))
            }
        }.getOrElse {
            Log.e(TAG, "Loading bundled preset '$name' failed", it)
            null
        }
    }

    /** Delete the named preset file. See [FolderPresets.delete]. */
    override suspend fun deletePreset(name: String): Boolean = presets.delete(name)
}
