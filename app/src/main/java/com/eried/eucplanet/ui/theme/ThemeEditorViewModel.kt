package com.eried.eucplanet.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the floating theme editor widget. Thin wrapper over [ThemeController]. */
@HiltViewModel
class ThemeEditorViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val themeController: ThemeController,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live, un-persisted preview colors (null when no edit in flight). */
    val live: StateFlow<AppThemeColors?> = themeController.live

    private val _choices = MutableStateFlow(
        ThemeChoices(BuiltInThemes.all.map { it.name }, emptyList(), emptyList(), folderAvailable = false)
    )
    val choices: StateFlow<ThemeChoices> = _choices.asStateFlow()

    fun refreshChoices() = viewModelScope.launch { _choices.value = themeController.availableThemes() }

    /** Instant in-memory preview while dragging (no IO). */
    fun preview(colors: AppThemeColors?) = themeController.previewLive(colors)

    /** Transient "blink" overlay for the target tool (separate from editing). */
    fun pulse(colors: AppThemeColors?) = themeController.pulse(colors)

    /** Persist the current preview into the working draft (on release). */
    fun commit() = viewModelScope.launch { themeController.commitLive() }

    fun selectTheme(name: String) = viewModelScope.launch {
        themeController.previewLive(null)
        themeController.selectTheme(name)
    }

    fun saveAs(name: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        themeController.commitLive()
        val ok = themeController.saveAs(name)
        if (ok) themeController.previewLive(null)
        onDone(ok)
    }

    fun closeEditor() = viewModelScope.launch {
        themeController.commitLive()
        themeController.previewLive(null)
        themeController.setEditorEnabled(false)
    }
}
