package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.flic.FlicManager
import dagger.hilt.android.lifecycle.HiltViewModel
import io.flic.flic2libandroid.Flic2Button
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FlicViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val flicManager: FlicManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val scanning: StateFlow<Boolean> = flicManager.scanning
    val scanStatus: StateFlow<String> = flicManager.scanStatus
    val pairedButtons: StateFlow<List<Flic2Button>> = flicManager.pairedButtons

    fun startScan() = flicManager.startScan()
    fun stopScan() = flicManager.stopScan()

    fun forgetButton(address: String) {
        val button = pairedButtons.value.find { it.bdAddr == address }
        if (button != null) flicManager.forgetButton(button)
    }

    private fun update(transform: AppSettings.() -> AppSettings) {
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(current.transform())
        }
    }

    fun updateFlic1Name(v: String) = update { copy(flic1Name = v) }
    fun updateFlic1Click(v: String) = update { copy(flic1Click = v) }
    fun updateFlic1DoubleClick(v: String) = update { copy(flic1DoubleClick = v) }
    fun updateFlic1Hold(v: String) = update { copy(flic1Hold = v) }
    fun updateFlic2Name(v: String) = update { copy(flic2Name = v) }
    fun updateFlic2Click(v: String) = update { copy(flic2Click = v) }
    fun updateFlic2DoubleClick(v: String) = update { copy(flic2DoubleClick = v) }
    fun updateFlic2Hold(v: String) = update { copy(flic2Hold = v) }
    fun updateFlic3Name(v: String) = update { copy(flic3Name = v) }
    fun updateFlic3Click(v: String) = update { copy(flic3Click = v) }
    fun updateFlic3DoubleClick(v: String) = update { copy(flic3DoubleClick = v) }
    fun updateFlic3Hold(v: String) = update { copy(flic3Hold = v) }
    fun updateFlic4Name(v: String) = update { copy(flic4Name = v) }
    fun updateFlic4Click(v: String) = update { copy(flic4Click = v) }
    fun updateFlic4DoubleClick(v: String) = update { copy(flic4DoubleClick = v) }
    fun updateFlic4Hold(v: String) = update { copy(flic4Hold = v) }
}
