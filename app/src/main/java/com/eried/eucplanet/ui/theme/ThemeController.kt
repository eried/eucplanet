package com.eried.eucplanet.ui.theme

import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** What the combo / widget can pick from. */
data class ThemeChoices(
    val builtIns: List<String>,
    val saved: List<String>,
    /** Base names that have an unsaved working draft (shown as "<name> (unsaved)"). */
    val unsaved: List<String>,
    val folderAvailable: Boolean,
)

/**
 * Single place that mutates the active theme, used by both the Settings combo
 * and the floating editor widget so they never drift.
 *
 * Built-in (and saved) themes are never mutated in place. Editing a theme writes
 * a **working draft** keyed by the base theme's name into `unsavedThemesJson`;
 * that draft shows in the combo as "<base> (unsaved)" and persists, so the rider
 * can switch to another theme and back. "Save as" writes a `.json` file via
 * [ThemeStore] (overwriting a saved theme of the same name; presets are files-less
 * so they can never be overwritten) and drops the draft.
 */
@Singleton
class ThemeController @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val themeStore: ThemeStore,
) {
    /**
     * In-memory live-preview channel. While the editor drags a slider we push
     * here (instant, no IO) so the whole app reskins at 60fps; we persist to the
     * working draft only on release / save. Null = no live edit in progress.
     */
    private val _live = MutableStateFlow<AppThemeColors?>(null)
    val live: StateFlow<AppThemeColors?> = _live.asStateFlow()

    fun previewLive(colors: AppThemeColors?) { _live.value = colors }

    /**
     * Transient "blink" channel for the target tool / play buttons. Takes
     * precedence over the edit preview when rendering, but is read separately so
     * pulsing a token never disturbs the value being edited.
     */
    private val _pulse = MutableStateFlow<AppThemeColors?>(null)
    val pulse: StateFlow<AppThemeColors?> = _pulse.asStateFlow()

    fun pulse(colors: AppThemeColors?) { _pulse.value = colors }

    /** Persist the current live preview into the working draft. No-op if idle. */
    suspend fun commitLive() {
        _live.value?.let { applyColors(it) }
    }

    /** Resolved colors of the active theme (the working draft if edited). */
    suspend fun currentColors(): AppThemeColors {
        val s = settingsRepository.get()
        return ThemeJson.colorsFromString(s.activeThemeColorsJson, BuiltInThemes.pureBlack.colors)
            ?: BuiltInThemes.pureBlack.colors
    }

    /** Built-ins (always) + saved customs + unsaved drafts. */
    suspend fun availableThemes(): ThemeChoices {
        val s = settingsRepository.get()
        val folder = themeStore.themesFolderAvailable()
        val saved = if (folder) themeStore.listSaved() else emptyList()
        val unsaved = parseDrafts(s.unsavedThemesJson).keys.toList()
        return ThemeChoices(themeStore.builtInNames(), saved, unsaved, folder)
    }

    /** Make a clean built-in or saved theme active. The dirty flag is cleared; any
     *  existing drafts are left untouched so the rider can switch back to them. */
    suspend fun selectTheme(name: String) {
        val colors = BuiltInThemes.byName(name)?.colors ?: themeStore.loadTheme(name) ?: return
        val s = settingsRepository.get()
        settingsRepository.update(
            s.copy(
                activeThemeColorsJson = ThemeJson.colorsToString(colors),
                activeThemeName = name,
                themeDirty = false,
            )
        )
        // Drop any lingering live/pulse preview so the newly-selected theme's
        // persisted colors actually render (otherwise the switch looks like a no-op).
        _live.value = null
        _pulse.value = null
    }

    /** Re-activate the unsaved working draft for [base], continuing as it was. */
    suspend fun selectUnsaved(base: String) {
        val s = settingsRepository.get()
        val colors = parseDrafts(s.unsavedThemesJson)[base] ?: return
        settingsRepository.update(
            s.copy(
                activeThemeColorsJson = ThemeJson.colorsToString(colors),
                activeThemeName = base,
                themeDirty = true,
            )
        )
        _live.value = null
        _pulse.value = null
    }

    /**
     * Apply an edit. The result is stored as the working draft for the current
     * base theme (creating that draft the first time a clean theme is edited), so
     * the base — preset or saved — is never mutated.
     */
    suspend fun applyColors(colors: AppThemeColors) {
        val s = settingsRepository.get()
        val base = s.activeThemeName.ifEmpty { BuiltInThemes.pureBlack.name }
        val drafts = parseDrafts(s.unsavedThemesJson)
        drafts[base] = colors
        settingsRepository.update(
            s.copy(
                activeThemeColorsJson = ThemeJson.colorsToString(colors),
                activeThemeName = base,
                themeDirty = true,
                unsavedThemesJson = serializeDrafts(drafts),
            )
        )
    }

    /**
     * Save the current working draft to the backup folder as [name] (overwriting
     * a saved theme of that name), then drop the draft and make the saved theme
     * the clean active theme.
     */
    suspend fun saveAs(name: String): Boolean {
        val ok = themeStore.saveTheme(name, currentColors())
        if (ok) {
            val s = settingsRepository.get()
            val drafts = parseDrafts(s.unsavedThemesJson)
            drafts.remove(s.activeThemeName)
            settingsRepository.update(
                s.copy(
                    activeThemeName = name,
                    themeDirty = false,
                    unsavedThemesJson = serializeDrafts(drafts),
                )
            )
        }
        return ok
    }

    suspend fun setEditorEnabled(enabled: Boolean) {
        val s = settingsRepository.get()
        settingsRepository.update(s.copy(themeEditorEnabled = enabled))
    }

    // --- Unsaved-draft (de)serialization: { "<base>": { isLight, colors{} } } ---

    private fun parseDrafts(json: String): LinkedHashMap<String, AppThemeColors> {
        val map = LinkedHashMap<String, AppThemeColors>()
        runCatching {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val co = obj.optJSONObject(key) ?: continue
                val floor = if (co.optBoolean("isLight", false)) BuiltInThemes.light.colors
                else BuiltInThemes.pureBlack.colors
                map[key] = ThemeJson.colorsFromJson(co, floor)
            }
        }
        return map
    }

    private fun serializeDrafts(map: Map<String, AppThemeColors>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, ThemeJson.colorsToJson(v)) }
        return obj.toString()
    }
}
