package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.PhoneImuSample
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.store.OverlayPresets
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.ui.studio.StudioImu
import com.eried.eucplanet.ui.studio.StudioTrips
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

// The Overlay Studio's ports on the iPhone, not wired yet: the studio row stays
// muted ("Not on iPhone yet") until phase 2 brings the AVFoundation camera,
// the AVAssetWriter export and the AVPlayer replay. These only keep the shared
// view model constructible; nothing registers them.

/** Layouts kept in memory: no backup folder and no bundled starters yet. */
internal class IosOverlayPresets : OverlayPresets {
    private var draft = OverlayPreset()
    override suspend fun loadDraft(): OverlayPreset = draft
    override suspend fun saveDraft(preset: OverlayPreset) { draft = preset }
    override suspend fun presetFolderAvailable(): Boolean = false
    override suspend fun listPresets(): List<String> = emptyList()
    override suspend fun savePreset(name: String, preset: OverlayPreset): Boolean = false
    override suspend fun loadPreset(name: String): OverlayPreset? = null
    override suspend fun listBundledPresets(): List<String> = emptyList()
    override suspend fun loadBundledPreset(name: String): OverlayPreset? = null
    override suspend fun deletePreset(name: String): Boolean = false
}

/** No fix and no trips to replay yet. */
internal object NoStudioTrips : StudioTrips {
    override val currentFix: StateFlow<PhoneLocation?> = MutableStateFlow<PhoneLocation?>(null).asStateFlow()
    override val allTrips: Flow<List<TripRecord>> = flowOf(emptyList())
    override val currentTripId: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
    override fun startLocationUpdates() = Unit
    override suspend fun tripById(id: Long): TripRecord? = null
    override fun tripCsv(record: TripRecord): String? = null
}

/** The accelerometer is not read on iOS yet. */
internal object NoStudioImu : StudioImu {
    override val imu: StateFlow<PhoneImuSample?> = MutableStateFlow<PhoneImuSample?>(null).asStateFlow()
    override fun start() = Unit
    override fun stop() = Unit
    override fun refresh() = Unit
}
