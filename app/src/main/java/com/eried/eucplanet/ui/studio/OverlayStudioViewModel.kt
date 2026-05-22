package com.eried.eucplanet.ui.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.OverlayElement
import com.eried.eucplanet.data.model.OverlayPreset
import com.eried.eucplanet.data.model.ViewportConfig
import com.eried.eucplanet.data.model.ViewportLayout
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.store.OverlayPresetStore
import kotlinx.coroutines.withContext
import com.eried.eucplanet.util.Units
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** One telemetry tick plus the wall-clock time it arrived, for graph elements. */
data class StudioSample(val timeMs: Long, val data: WheelData)

/** Photo output format for a Replay-mode snapshot. */
enum class ReplayPhotoFormat(
    /** True when the format carries an alpha channel (no chroma fill needed). */
    val hasAlpha: Boolean
) {
    PNG(true),
    JPG(false),
    WEBP(true);

    companion object {
        fun fromKey(key: String): ReplayPhotoFormat =
            entries.firstOrNull { it.name == key } ?: PNG
    }
}

/** Video output format for a Replay-mode clip export. */
enum class ReplayVideoFormat(
    /** True when the format carries an alpha channel (no chroma fill needed). */
    val hasAlpha: Boolean
) {
    GIF(true),   // 1-bit transparency
    APNG(true),  // full RGBA alpha
    MP4(false);  // opaque — needs a chroma fill

    companion object {
        fun fromKey(key: String): ReplayVideoFormat =
            entries.firstOrNull { it.name == key } ?: GIF
    }
}

/** The rider's Replay-mode output-format choices. */
data class ReplayExportPrefs(
    val photoFormat: ReplayPhotoFormat = ReplayPhotoFormat.PNG,
    val videoFormat: ReplayVideoFormat = ReplayVideoFormat.GIF,
    /** ARGB chroma fill for alpha-less formats (JPG, MP4). */
    val chromaColor: Long = 0xFFFF00FFL,
    /**
     * When exporting an alpha-less format (JPG, MP4), override every overlay
     * element to 100% opacity so half-transparent elements don't blend oddly
     * with the chroma fill.
     */
    val forceOpaque: Boolean = true
)

/** Outcome of a "save preset" attempt, surfaced to the UI as a snackbar. */
enum class PresetSaveResult { SAVED, NO_FOLDER, FAILED }

/**
 * Holds the working Overlay Studio layout and feeds the studio screen its live
 * telemetry. The layout is a single [OverlayPreset] mutated through the `update`
 * helpers; every change is debounce-persisted to the throwaway draft file so
 * reopening the studio restores exactly what the rider was building.
 */
@HiltViewModel
class OverlayStudioViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val presetStore: OverlayPresetStore,
    private val tripRepository: TripRepository
) : ViewModel() {

    companion object {
        /** Keep this many seconds of telemetry for graph elements. */
        private const val HISTORY_SECONDS = 360
        private const val DRAFT_DEBOUNCE_MS = 600L
        /** Hard cap on overlay elements in one layout. */
        const val MAX_ELEMENTS = 32
    }

    private val initialSettings = runBlocking(Dispatchers.IO) { settingsRepository.get() }

    // --- Working layout ------------------------------------------------------
    private val _preset = MutableStateFlow(OverlayPreset())
    val preset: StateFlow<OverlayPreset> = _preset.asStateFlow()

    private val _selectedElementId = MutableStateFlow<String?>(null)
    val selectedElementId: StateFlow<String?> = _selectedElementId.asStateFlow()

    /** True once the working layout has edits since it was loaded / cleared. */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    // --- Live telemetry ------------------------------------------------------
    // Merge the phone's live GPS fix into the telemetry stream the same way the
    // repository merges the IMU — the MAP overlay reads latitude / longitude
    // from WheelData. WheelRepository can't inject TripRepository (that would
    // be circular), so the GPS merge lands here where TripRepository is already
    // available. In replay the lat/lon come from the trip CSV instead.
    val wheelData: StateFlow<WheelData> = combine(
        wheelRepository.wheelData,
        tripRepository.currentLocation
    ) { data, loc ->
        if (loc != null) data.copy(latitude = loc.latitude, longitude = loc.longitude)
        else data
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WheelData())

    val connected: StateFlow<Boolean> = wheelRepository.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val wheelName: StateFlow<String> = combine(
        wheelRepository.modelName,
        wheelRepository.connectedBrand,
        wheelRepository.connectedDeviceName
    ) { model, brand, device ->
        model ?: brand ?: device ?: ""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val speedUnit: String = Units.effectiveSpeedUnit(initialSettings)
    val distanceUnit: String = Units.effectiveDistanceUnit(initialSettings)
    val tempUnit: String = Units.effectiveTempUnit(initialSettings)

    // --- Replay export format prefs -----------------------------------------
    // The Replay panel's output-format chooser. Seeded from saved settings;
    // every change is persisted so the rider's choice sticks across sessions.
    private val _replayExportPrefs = MutableStateFlow(
        ReplayExportPrefs(
            photoFormat = ReplayPhotoFormat.fromKey(initialSettings.studioReplayPhotoFormat),
            videoFormat = ReplayVideoFormat.fromKey(initialSettings.studioReplayVideoFormat),
            chromaColor = initialSettings.studioReplayChromaColor,
            forceOpaque = initialSettings.studioReplayForceOpaque
        )
    )
    val replayExportPrefs: StateFlow<ReplayExportPrefs> = _replayExportPrefs.asStateFlow()

    fun setReplayPhotoFormat(format: ReplayPhotoFormat) {
        _replayExportPrefs.value = _replayExportPrefs.value.copy(photoFormat = format)
        persistReplayExportPrefs()
    }

    fun setReplayVideoFormat(format: ReplayVideoFormat) {
        _replayExportPrefs.value = _replayExportPrefs.value.copy(videoFormat = format)
        persistReplayExportPrefs()
    }

    fun setReplayChromaColor(color: Long) {
        _replayExportPrefs.value = _replayExportPrefs.value.copy(chromaColor = color)
        persistReplayExportPrefs()
    }

    fun setReplayForceOpaque(force: Boolean) {
        _replayExportPrefs.value = _replayExportPrefs.value.copy(forceOpaque = force)
        persistReplayExportPrefs()
    }

    private fun persistReplayExportPrefs() {
        val prefs = _replayExportPrefs.value
        viewModelScope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(
                current.copy(
                    studioReplayPhotoFormat = prefs.photoFormat.name,
                    studioReplayVideoFormat = prefs.videoFormat.name,
                    studioReplayChromaColor = prefs.chromaColor,
                    studioReplayForceOpaque = prefs.forceOpaque
                )
            )
        }
    }

    private val _history = MutableStateFlow<List<StudioSample>>(emptyList())
    val history: StateFlow<List<StudioSample>> = _history.asStateFlow()

    // --- Preset folder / saved presets --------------------------------------
    private val _folderAvailable = MutableStateFlow(false)
    val folderAvailable: StateFlow<Boolean> = _folderAvailable.asStateFlow()

    private val _savedPresets = MutableStateFlow<List<String>>(emptyList())
    val savedPresets: StateFlow<List<String>> = _savedPresets.asStateFlow()

    /** Read-only upright starter presets shipped with the app. */
    private val _bundledPresets = MutableStateFlow<List<String>>(emptyList())
    val bundledPresets: StateFlow<List<String>> = _bundledPresets.asStateFlow()

    /** Read-only landscape starter presets (their elements are pre-rotated). */
    private val _bundledLandscapePresets = MutableStateFlow<List<String>>(emptyList())
    val bundledLandscapePresets: StateFlow<List<String>> =
        _bundledLandscapePresets.asStateFlow()

    // --- Replay (recorded trips) --------------------------------------------
    /** Recorded trips available to replay, newest first. */
    val trips: StateFlow<List<TripRecord>> = tripRepository.allTrips
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Looks up a single trip by id — used when the studio opens from Share. */
    suspend fun tripById(id: Long): TripRecord? =
        withContext(Dispatchers.IO) { tripRepository.getTripById(id) }

    /** Reads a trip's CSV off the main thread and parses it into a timeline. */
    suspend fun loadReplayTrip(record: TripRecord): ReplayTrip? =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = tripRepository.getTripFile(record)
                if (file.exists()) parseTripCsv(file.readText()) else null
            }.getOrNull()
        }

    private var draftSaveJob: Job? = null

    init {
        // The MAP overlay needs live GPS even when the studio is opened with no
        // wheel connected and no trip recording. Idempotent — TripRepository
        // guards against starting a second fused-location request.
        tripRepository.startLocationUpdates()
        viewModelScope.launch {
            _preset.value = presetStore.loadDraft()
        }
        viewModelScope.launch {
            // Split starter presets by content: a preset whose elements are
            // mostly rotated is treated as a landscape layout.
            val portrait = mutableListOf<String>()
            val landscape = mutableListOf<String>()
            presetStore.listBundledPresets().forEach { name ->
                val els = presetStore.loadBundledPreset(name)?.elements
                if (els != null && els.isNotEmpty() &&
                    els.count { it.rotationDeg != 0f } * 2 > els.size
                ) {
                    landscape.add(name)
                } else {
                    portrait.add(name)
                }
            }
            _bundledPresets.value = portrait
            _bundledLandscapePresets.value = landscape
        }
        viewModelScope.launch {
            wheelRepository.wheelData.collect { data ->
                val now = System.currentTimeMillis()
                val cutoff = now - HISTORY_SECONDS * 1000L
                _history.value = (_history.value + StudioSample(now, data))
                    .dropWhile { it.timeMs < cutoff }
            }
        }
        refreshFolderState()
    }

    /** Re-check whether a backup folder is configured and list saved presets. */
    fun refreshFolderState() {
        viewModelScope.launch {
            _folderAvailable.value = presetStore.presetFolderAvailable()
            _savedPresets.value = if (_folderAvailable.value) presetStore.listPresets()
            else emptyList()
        }
    }

    // --- Layout mutators -----------------------------------------------------

    private fun mutate(
        markDirty: Boolean = true,
        transform: (OverlayPreset) -> OverlayPreset
    ) {
        _preset.value = transform(_preset.value)
        if (markDirty) _dirty.value = true
        scheduleDraftSave()
    }

    fun setLayout(layout: ViewportLayout) = mutate { it.withLayout(layout) }

    fun setDividers(dividers: List<Float>) = mutate { it.copy(dividers = dividers) }

    fun setViewport(index: Int, config: ViewportConfig) = mutate { p ->
        p.copy(viewports = p.viewports.toMutableList().also {
            if (index in it.indices) it[index] = config
        })
    }

    fun setDividerStyle(color: Long, thickness: Float) = mutate { p ->
        p.copy(dividerColor = color, dividerThickness = thickness)
    }

    fun addElement(element: OverlayElement) {
        if (_preset.value.elements.size >= MAX_ELEMENTS) return
        mutate { it.copy(elements = it.elements + element) }
        _selectedElementId.value = element.id
    }

    fun updateElement(element: OverlayElement) = mutate { p ->
        p.copy(elements = p.elements.map { if (it.id == element.id) element else it })
    }

    fun removeElement(id: String) {
        mutate { p -> p.copy(elements = p.elements.filterNot { it.id == id }) }
        if (_selectedElementId.value == id) _selectedElementId.value = null
    }

    /** Raise an element to the top of the draw order (used when it is tapped). */
    fun bringToFront(id: String) = mutate(markDirty = false) { p ->
        val el = p.elements.firstOrNull { it.id == id } ?: return@mutate p
        p.copy(elements = p.elements.filterNot { it.id == id } + el)
    }

    /** Move an element within the draw order — drives the Manage-elements list. */
    fun moveElement(from: Int, to: Int) = mutate { p ->
        if (from !in p.elements.indices || to !in p.elements.indices || from == to) {
            return@mutate p
        }
        p.copy(elements = p.elements.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun selectElement(id: String?) { _selectedElementId.value = id }

    fun selectedElement(): OverlayElement? =
        _preset.value.elements.firstOrNull { it.id == _selectedElementId.value }

    // --- Preset files --------------------------------------------------------

    fun savePresetAs(name: String, onResult: (PresetSaveResult) -> Unit) {
        viewModelScope.launch {
            if (!presetStore.presetFolderAvailable()) {
                onResult(PresetSaveResult.NO_FOLDER)
                return@launch
            }
            val ok = presetStore.savePreset(name, _preset.value)
            if (ok) refreshFolderState()
            onResult(if (ok) PresetSaveResult.SAVED else PresetSaveResult.FAILED)
        }
    }

    fun loadPreset(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val loaded = presetStore.loadPreset(name)
            if (loaded != null) {
                _preset.value = loaded
                _selectedElementId.value = null
                _dirty.value = false
                scheduleDraftSave()
            }
            onResult(loaded != null)
        }
    }

    fun loadBundledPreset(name: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val loaded = presetStore.loadBundledPreset(name)
            if (loaded != null) {
                // Bundled presets are templates — drop the name so a later save
                // does not silently shadow the read-only original.
                _preset.value = loaded.copy(name = "")
                _selectedElementId.value = null
                _dirty.value = false
                scheduleDraftSave()
            }
            onResult(loaded != null)
        }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            presetStore.deletePreset(name)
            refreshFolderState()
        }
    }

    /** Wipe the working layout back to a single empty camera viewport. */
    fun clearLayout() {
        _preset.value = OverlayPreset()
        _selectedElementId.value = null
        _dirty.value = false
        scheduleDraftSave()
    }

    private fun scheduleDraftSave() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            presetStore.saveDraft(_preset.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Flush the draft synchronously so nothing in flight is lost.
        runBlocking { presetStore.saveDraft(_preset.value) }
    }
}
