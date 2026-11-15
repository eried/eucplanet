package com.eried.eucplanet.data.store

import com.eried.eucplanet.hud.protocol.OverlayPreset

/**
 * The Overlay Studio's layouts on disk: the throwaway working draft, the
 * rider's named presets (one `.json` each, written by [OverlayPresetJson], in
 * the backup folder's `overlays/`), and the read-only starters the app ships.
 * Android keeps OverlayPresetStore (app-private draft, the SAF backup folder,
 * the APK's assets); iOS supplies its own.
 */
interface OverlayPresets {
    /** The working layout, or the bundled default before the first edit. */
    suspend fun loadDraft(): OverlayPreset
    /** Persists the working layout. Best-effort. */
    suspend fun saveDraft(preset: OverlayPreset)
    /** True when a backup folder is set, which named presets need. */
    suspend fun presetFolderAvailable(): Boolean
    /** Saved preset names, sorted case-insensitively. */
    suspend fun listPresets(): List<String>
    /** Writes [preset] as `<name>.json`, overwriting one of that name. */
    suspend fun savePreset(name: String, preset: OverlayPreset): Boolean
    /** The named preset, or null when missing or unreadable. */
    suspend fun loadPreset(name: String): OverlayPreset?
    /** The starter presets shipped with the app. */
    suspend fun listBundledPresets(): List<String>
    /** One starter preset, or null. */
    suspend fun loadBundledPreset(name: String): OverlayPreset?
    /** Deletes the named preset. */
    suspend fun deletePreset(name: String): Boolean
}
