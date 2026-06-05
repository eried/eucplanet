package com.eried.eucplanet.ui.theme

import android.content.Context
import android.content.res.Configuration
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.ThemeStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * **Persistence model:** settings store only the active theme's *name*
 * ([AppSettings.activeThemeName]). The resolved colors are NOT persisted — they
 * live here in memory ([activeColors]) and are re-derived on launch from the
 * name: a built-in from code, or a saved `.json` from the themes folder, falling
 * back to a preset if the file is gone. Nothing theme-related rides along in the
 * settings backup except the name.
 *
 * Built-in (and saved) themes are never mutated in place. Editing a theme writes
 * an **in-memory** working draft keyed by the base theme's name into [drafts];
 * that draft shows in the combo as "<base> (unsaved)" for this process only and
 * is intentionally lost on app kill (that is what "unsaved" means). "Save as"
 * writes a `.json` file via [ThemeStore] and drops the draft.
 */
@Singleton
class ThemeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val themeStore: ThemeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveMutex = Mutex()
    @Volatile private var resolved = false

    /**
     * The resolved colors of the active theme — the single render source of
     * truth. Re-derived from the persisted name on launch (see [ensureResolved]).
     */
    private val _activeColors = MutableStateFlow(BuiltInThemes.pureBlack.colors)
    val activeColors: StateFlow<AppThemeColors> = _activeColors.asStateFlow()

    /** True when the active theme is an unsaved working draft. In-memory only. */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /**
     * Unsaved working drafts, keyed by the base theme they were forked from.
     * In-memory only — lost on app kill. Re-activating a draft lets the rider
     * switch away to a preset and back without losing edits within a session.
     */
    private val drafts = LinkedHashMap<String, AppThemeColors>()

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

    init { scope.launch { ensureResolved() } }

    /**
     * Resolve the active colors from the persisted theme name, once. On a first
     * upgrade (no name yet) this seeds the name from the legacy themeMode/accent
     * and persists it, so the OS stops driving the theme thereafter.
     */
    suspend fun ensureResolved() {
        if (resolved) return
        resolveMutex.withLock {
            if (resolved) return
            val s = settingsRepository.get()
            if (s.activeThemeName.isNotEmpty()) {
                _activeColors.value = resolveByName(s.activeThemeName) ?: BuiltInThemes.pureBlack.colors
            } else {
                // First upgrade from the legacy theme settings: pick a built-in by
                // name and persist it; render its (clean) colors.
                val migrated = ThemeMigration.migrate(s.themeMode, s.accentColor, systemDark())
                val builtIn = BuiltInThemes.byName(migrated.name) ?: BuiltInThemes.pureBlack
                _activeColors.value = builtIn.colors
                settingsRepository.update(settingsRepository.get().copy(activeThemeName = builtIn.name))
            }
            resolved = true
        }
    }

    /** Built-in from code, else saved `.json` from the themes folder, else null. */
    private suspend fun resolveByName(name: String): AppThemeColors? =
        BuiltInThemes.byName(name)?.colors ?: themeStore.loadTheme(name)

    /**
     * Synchronous best-effort seed of [activeColors] from the persisted theme
     * NAME, so the very FIRST composed frame already carries the rider's theme --
     * even on a process-death resume (e.g. returning from an external browser
     * link) where the async [ensureResolved] would otherwise leave pure black for
     * a frame and flash the whole screen. Only the built-in case is handled here
     * (instant, no IO); a saved theme or an empty name is finished by
     * [ensureResolved]. Call from the UI's synchronous startup with the already-
     * loaded [AppSettings.activeThemeName] so no extra DataStore read is needed.
     */
    fun seedSync(activeThemeName: String) {
        if (resolved) return
        BuiltInThemes.byName(activeThemeName)?.let {
            _activeColors.value = it.colors
            resolved = true
        }
    }

    private fun systemDark(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Resolved colors of the active theme (the working draft if edited). */
    suspend fun currentColors(): AppThemeColors {
        ensureResolved()
        return _activeColors.value
    }

    /** Built-ins (always) + saved customs + in-memory unsaved drafts. */
    suspend fun availableThemes(): ThemeChoices {
        ensureResolved()
        val folder = themeStore.themesFolderAvailable()
        val saved = if (folder) themeStore.listSaved() else emptyList()
        return ThemeChoices(themeStore.builtInNames(), saved, drafts.keys.toList(), folder)
    }

    /** Make a clean built-in or saved theme active. The dirty flag is cleared; any
     *  existing drafts are left untouched so the rider can switch back to them. */
    suspend fun selectTheme(name: String) {
        val colors = resolveByName(name) ?: return
        _activeColors.value = colors
        _dirty.value = false
        // Drop any lingering live/pulse preview so the newly-selected theme's
        // colors actually render (otherwise the switch looks like a no-op).
        _live.value = null
        _pulse.value = null
        settingsRepository.update(settingsRepository.get().copy(activeThemeName = name))
    }

    /** Re-activate the in-memory working draft for [base], continuing as it was. */
    suspend fun selectUnsaved(base: String) {
        val colors = drafts[base] ?: return
        _activeColors.value = colors
        _dirty.value = true
        _live.value = null
        _pulse.value = null
        settingsRepository.update(settingsRepository.get().copy(activeThemeName = base))
    }

    /**
     * Apply an edit. The result becomes the in-memory working draft for the
     * current base theme (creating that draft the first time a clean theme is
     * edited), so the base — preset or saved — is never mutated.
     */
    suspend fun applyColors(colors: AppThemeColors) {
        ensureResolved()
        val base = settingsRepository.get().activeThemeName.ifEmpty { BuiltInThemes.pureBlack.name }
        drafts[base] = colors
        _activeColors.value = colors
        _dirty.value = true
        if (settingsRepository.get().activeThemeName.isEmpty()) {
            settingsRepository.update(settingsRepository.get().copy(activeThemeName = base))
        }
    }

    /** Persist the current live preview into the working draft. No-op if idle. */
    suspend fun commitLive() {
        _live.value?.let { applyColors(it) }
    }

    /**
     * Save the current working draft to the backup folder as [name] (overwriting
     * a saved theme of that name), then drop the draft and make the saved theme
     * the clean active theme.
     */
    suspend fun saveAs(name: String): Boolean {
        val ok = themeStore.saveTheme(name, currentColors())
        if (ok) {
            drafts.remove(settingsRepository.get().activeThemeName)
            _dirty.value = false
            settingsRepository.update(settingsRepository.get().copy(activeThemeName = name))
        }
        return ok
    }

    suspend fun setEditorEnabled(enabled: Boolean) {
        settingsRepository.update(settingsRepository.get().copy(themeEditorEnabled = enabled))
    }
}
