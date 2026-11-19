package com.eried.eucplanet.app

import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.PhoneImuSample
import com.eried.eucplanet.data.repository.PhoneLocation
import com.eried.eucplanet.data.store.OverlayPresetJson
import com.eried.eucplanet.data.store.OverlayPresets
import com.eried.eucplanet.data.sync.BackupFolder
import com.eried.eucplanet.data.sync.FolderPresets
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.toNSData
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.ui.studio.StudioImu
import com.eried.eucplanet.ui.studio.StudioTrips
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

// The Overlay Studio's ports on the iPhone: the layouts on disk, the trips to
// replay and the phone's accelerometer, the three things the shared
// OverlayStudioViewModel asks of the phone (StudioPorts.kt, OverlayPresets).

/**
 * The studio's layouts, as Android's OverlayPresetStore keeps them: the
 * working draft in the app's own files (Documents/overlay_studio_draft.json),
 * the rider's named presets one `<name>.json` each in the backup folder's
 * overlays/ (the shared [FolderPresets], Android's files), or in
 * Documents/overlays while no folder is set ([root], [IosFolderSync.jsonRoot]),
 * mirrored with Dropbox's /overlays like the themes, and the starters the app
 * ships, the phone app's assets/overlay_presets copied in by generateIosAppArt.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosOverlayPresets(
    documents: String,
    root: suspend () -> BackupFolder = { IosPathFolder(documents) },
) : OverlayPresets {
    private val draftPath = "$documents/$DRAFT_FILE"
    private val presets = FolderPresets(root)

    private fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    private fun writeText(path: String, text: String): Boolean =
        text.encodeToByteArray().toNSData().writeToFile(path, true)

    override suspend fun loadDraft(): OverlayPreset = withContext(Dispatchers.IO) {
        val text = readText(draftPath) ?: return@withContext bundledDefault() ?: OverlayPreset()
        runCatching { OverlayPresetJson.fromJson(JSONObject(text)) }.getOrElse {
            Log.w(TAG, "Draft unreadable, starting fresh", it)
            bundledDefault() ?: OverlayPreset()
        }
    }

    /** The first-launch layout, as Android reads assets/overlay_default.json. */
    private suspend fun bundledDefault(): OverlayPreset? = runCatching {
        OverlayPresetJson.fromJson(JSONObject(Res.readBytes("files/$DEFAULT_ASSET").decodeToString()))
    }.getOrElse {
        Log.w(TAG, "Bundled default preset unreadable", it)
        null
    }

    override suspend fun saveDraft(preset: OverlayPreset) = withContext(Dispatchers.IO) {
        if (!writeText(draftPath, OverlayPresetJson.toJson(preset).toString())) {
            Log.w(TAG, "Could not save draft")
        }
    }

    // Documents is always there, so named presets always are.
    override suspend fun presetFolderAvailable(): Boolean = presets.available()

    override suspend fun listPresets(): List<String> = presets.list()

    /** Written as the shared FolderPresets writes it: SyncManager.sanitizeBackupName's name, in place. */
    override suspend fun savePreset(name: String, preset: OverlayPreset): Boolean = presets.save(name, preset)

    override suspend fun loadPreset(name: String): OverlayPreset? = presets.load(name)

    override suspend fun listBundledPresets(): List<String> = runCatching {
        Res.readBytes("files/$BUNDLED_DIR/$BUNDLED_INDEX").decodeToString()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .sortedBy { it.lowercase() }
    }.getOrElse {
        Log.w(TAG, "Could not list bundled presets", it)
        emptyList()
    }

    override suspend fun loadBundledPreset(name: String): OverlayPreset? = runCatching {
        OverlayPresetJson.fromJson(JSONObject(Res.readBytes("files/$BUNDLED_DIR/$name$SUFFIX").decodeToString()))
    }.getOrElse {
        Log.e(TAG, "Loading bundled preset '$name' failed", it)
        null
    }

    override suspend fun deletePreset(name: String): Boolean = presets.delete(name)

    private companion object {
        const val TAG = "OverlayPresets"
        const val DRAFT_FILE = "overlay_studio_draft.json"
        const val SUFFIX = ".json"
        const val BUNDLED_DIR = "overlay_presets"
        /** The starters' names, one per line, written by generateIosAppArt (Compose resources cannot list a folder). */
        const val BUNDLED_INDEX = "index.txt"
        const val DEFAULT_ASSET = "overlay_default.json"
    }
}

/** The trip store and the phone's location, as AndroidStudioTrips reads TripRepository. */
internal object IosStudioTrips : StudioTrips {
    override val currentFix: StateFlow<PhoneLocation?> get() = IosLocation.fix
    override val allTrips: Flow<List<TripRecord>> get() = IosTrips.allTrips
    override val currentTripId: StateFlow<Long?> get() = IosTrips.currentTripId
    override fun startLocationUpdates() = IosTrips.startLocationUpdates()
    override suspend fun tripById(id: Long): TripRecord? = IosTrips.getTripById(id)

    @OptIn(ExperimentalForeignApi::class)
    override fun tripCsv(record: TripRecord): String? =
        NSString.stringWithContentsOfFile("${IosTrips.tripsDir}/${record.fileName}", NSUTF8StringEncoding, null)
}

/**
 * The phone's linear acceleration, as Android's PhoneSensorRepository reads
 * TYPE_LINEAR_ACCELERATION at SENSOR_DELAY_GAME: CoreMotion's user
 * acceleration (gravity removed, in g) at 50 Hz. Core Motion reports with the
 * opposite sign to Android's sensors, so each axis is flipped; the axes are
 * the same (x right, y up, z out of the screen).
 *
 * One sensor for everyone, counted like Android's: the wheel state holds it
 * while a wheel is connected or a trip records (the G-force tiles and the
 * trip's G columns), the Overlay Studio and the Data sources sheet while
 * they are open, and the last stop lets the sensor go.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosPhoneImu : StudioImu {
    private val manager by lazy { CMMotionManager() }
    private val _imu = MutableStateFlow<PhoneImuSample?>(null)
    override val imu: StateFlow<PhoneImuSample?> = _imu.asStateFlow()

    // The screens start and stop on the main thread, the wheel state from
    // its own.
    private val lock = SynchronizedObject()
    private var users = 0

    // Its own queue, as Android reads the sensor on its own thread: 50
    // samples a second must not wait behind the screen.
    private val queue by lazy { NSOperationQueue().apply { maxConcurrentOperationCount = 1 } }

    override fun start() = synchronized(lock) {
        users++
        if (users == 1) register()
    }

    override fun stop() = synchronized(lock) {
        if (users == 0) return@synchronized
        users--
        if (users == 0) {
            manager.stopDeviceMotionUpdates()
            _imu.value = null
        }
    }

    // CoreMotion keeps delivering after the app comes back; a re-register is
    // only needed when it was stopped.
    override fun refresh() = synchronized(lock) {
        if (users > 0 && !manager.deviceMotionActive) register()
    }

    private fun register() {
        if (!manager.deviceMotionAvailable) return
        manager.deviceMotionUpdateInterval = 0.02
        manager.startDeviceMotionUpdatesToQueue(queue) { motion, _ ->
            motion?.userAcceleration?.useContents {
                _imu.value = PhoneImuSample(xG = (-x).toFloat(), yG = (-y).toFloat(), zG = (-z).toFloat())
            }
        }
    }
}
