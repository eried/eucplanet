package com.eried.eucplanet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.tpms.TpmsDiscoveredSensor
import com.eried.eucplanet.ble.tpms.TpmsReading
import com.eried.eucplanet.ble.tpms.TpmsSensor
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TpmsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Screen state for the TPMS settings page.
 *  - [sensors]: rider's bound sensors, each paired with its latest live
 *    reading when one has arrived this session.
 *  - [discovering]: true while the rider has the "Add sensor" sheet open
 *    and we're listening to nearby adverts.
 *  - [discovered]: every sensor the scanner currently sees, regardless of
 *    bind state. The sheet filters out already-bound ids.
 *  - [pressureUnit]: "kpa" / "bar" / "psi"; lets the screen pre-format
 *    every pressure value without hitting the repository again.
 */
data class TpmsUiState(
    val sensors: List<Pair<TpmsSensor, TpmsReading?>> = emptyList(),
    val discovering: Boolean = false,
    val discovered: List<TpmsDiscoveredSensor> = emptyList(),
    val pressureUnit: String = "kpa",
)

@HiltViewModel
class TpmsViewModel @Inject constructor(
    private val tpmsRepository: TpmsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _discovering = MutableStateFlow(false)
    private val _discovered = MutableStateFlow<List<TpmsDiscoveredSensor>>(emptyList())
    private var discoverJob: Job? = null

    val state: StateFlow<TpmsUiState> = combine(
        tpmsRepository.bound,
        tpmsRepository.readings,
        _discovering,
        _discovered,
        settingsRepository.settings,
    ) { bound, readings, discovering, discovered, settings ->
        TpmsUiState(
            sensors = bound.map { it to readings[it.id6Hex] },
            discovering = discovering,
            // Unbound sensors only -- avoid re-listing a sensor the
            // rider has already bound (it shows in the main list).
            discovered = discovered.filterNot { d ->
                bound.any { it.id6Hex == d.id6Hex }
            },
            pressureUnit = settings.pressureUnit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TpmsUiState())

    /** Start the nearby-scan stream; auto-stops via [stopDiscover]. */
    fun startDiscover() {
        if (discoverJob != null) return
        _discovering.value = true
        _discovered.value = emptyList()
        discoverJob = viewModelScope.launch {
            tpmsRepository.discoverNearby().collect { hit ->
                // Replace-or-append by sensor id so the list stays stable
                // when the same sensor advertises repeatedly.
                val current = _discovered.value.toMutableList()
                val existing = current.indexOfFirst { it.id6Hex == hit.id6Hex }
                if (existing >= 0) current[existing] = hit else current.add(hit)
                _discovered.value = current
            }
        }
    }

    fun stopDiscover() {
        discoverJob?.cancel()
        discoverJob = null
        _discovering.value = false
        _discovered.value = emptyList()
    }

    fun bind(id6Hex: String, label: String = "") {
        viewModelScope.launch { tpmsRepository.bind(id6Hex, label) }
    }

    fun unbind(id6Hex: String) {
        viewModelScope.launch { tpmsRepository.unbind(id6Hex) }
    }

    fun rename(id6Hex: String, label: String) {
        viewModelScope.launch { tpmsRepository.rename(id6Hex, label) }
    }

    fun setPressureUnit(unit: String) {
        viewModelScope.launch {
            settingsRepository.update(settingsRepository.get().copy(pressureUnit = unit))
        }
    }

    override fun onCleared() {
        stopDiscover()
    }
}
