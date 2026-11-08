package com.eried.eucplanet.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.DropboxLinkScratch
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** DataStore in memory: the settings blob without a file. */
class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

/** A trips folder in memory. Times are the phone's, in ms. */
class MemoryTripFiles(private val clock: () -> Long) : TripFileStore {
    class Stored(var bytes: ByteArray, var modifiedMs: Long)

    val stored = LinkedHashMap<String, Stored>()

    fun write(name: String, text: String) {
        stored[name] = Stored(text.encodeToByteArray(), clock())
    }

    fun text(name: String): String? = stored[name]?.bytes?.decodeToString()

    inner class File(override val name: String) : TripFile {
        private val s get() = stored[name]
        override fun exists() = s != null
        override fun length() = s?.bytes?.size?.toLong() ?: 0L
        override fun lastModified() = s?.modifiedMs ?: 0L
        override fun readBytes() = s?.bytes ?: error("$name is gone")
        override fun setLastModified(ms: Long): Boolean {
            s?.modifiedMs = ms
            return s != null
        }
        override fun delete() = stored.remove(name) != null
        override fun <R> useLines(block: (Sequence<String>) -> R): R = block(readBytes().decodeToString().lineSequence())
    }

    override fun files(): List<TripFile> = stored.keys.map { File(it) }
    override fun file(name: String): TripFile = File(name)
    override fun writeWhole(name: String, bytes: ByteArray): TripFile {
        stored[name] = Stored(bytes, clock())
        return File(name)
    }
}

/** Room's TripDao over a list, with its NOCASE file-name matches. */
class MemoryTripDao : TripDao {
    private val rows = MutableStateFlow<List<TripRecord>>(emptyList())
    private var nextId = 1L

    val all: List<TripRecord> get() = rows.value
    fun byName(name: String) = rows.value.firstOrNull { it.fileName.equals(name, ignoreCase = true) }

    private fun edit(where: (TripRecord) -> Boolean, change: (TripRecord) -> TripRecord) {
        rows.value = rows.value.map { if (where(it)) change(it) else it }
    }
    private fun named(name: String): (TripRecord) -> Boolean = { it.fileName.equals(name, ignoreCase = true) }

    override fun observeAll(): Flow<List<TripRecord>> = rows.map { list -> list.sortedByDescending { it.startTime } }
    override suspend fun insert(trip: TripRecord): Long {
        val id = if (trip.id != 0L) trip.id else nextId++
        rows.value = rows.value + trip.copy(id = id)
        return id
    }
    override suspend fun update(trip: TripRecord) = edit({ it.id == trip.id }) { trip }
    override suspend fun delete(trip: TripRecord) { rows.value = rows.value.filter { it.id != trip.id } }
    override fun observeCount(): Flow<Int> = rows.map { it.size }
    override suspend fun deleteAll() { rows.value = emptyList() }
    override suspend fun getPendingUploads() =
        rows.value.filter { it.endTime != null && it.uploadStatus in setOf(1, 3, 4) }.sortedByDescending { it.startTime }
    override suspend fun markMirrorIfAbsent(id: Long) = edit({ it.id == id }) { it.copy(uploadStatus = 4) }
    override suspend fun setUploadStatus(id: Long, status: Int) = edit({ it.id == id }) { it.copy(uploadStatus = status) }
    override suspend fun markUploaded(id: Long, at: Long) = edit({ it.id == id }) { it.copy(uploadStatus = 2, uploadedAt = at) }
    override suspend fun markFinalized(id: Long, folder: Boolean, eucstats: Boolean) = edit({ it.id == id }) {
        it.copy(
            uploadStatus = if (folder) 1 else it.uploadStatus,
            eucstatsStatus = if (eucstats) 1 else it.eucstatsStatus,
        )
    }
    override suspend fun getById(id: Long) = rows.value.firstOrNull { it.id == id }
    override suspend fun updateWheelMeta(id: Long, json: String?) = edit({ it.id == id }) { it.copy(wheelMetaJson = json) }
    override suspend fun updateCustomName(id: Long, name: String?) = edit({ it.id == id }) { it.copy(customName = name) }
    override suspend fun markPendingFolderUpload(id: Long) = edit({ it.id == id }) { it.copy(uploadStatus = 1) }
    override suspend fun setDropboxStatusByName(fileName: String, status: Int, at: Long?) =
        edit(named(fileName)) { it.copy(dropboxStatus = status, dropboxUploadedAt = at) }
    override suspend fun setDropboxSyncedHash(fileName: String, hash: String?) =
        edit(named(fileName)) { it.copy(dropboxSyncedHash = hash) }
    override suspend fun setDropboxStatus(id: Long, status: Int) = edit({ it.id == id }) { it.copy(dropboxStatus = status) }
    override suspend fun allWheelMeta() = rows.value.mapNotNull { it.wheelMetaJson }
    override suspend fun tripsWithoutWheelMeta() = rows.value.filter { it.wheelMetaJson == null && it.endTime != null }
    override suspend fun getUnfinished() = rows.value.filter { it.endTime == null }
    override suspend fun findByFileName(name: String) = byName(name)
    override suspend fun allFileNames() = rows.value.map { it.fileName }
    override suspend fun getPendingEucstatsUploads() = rows.value
        .filter { it.endTime != null && it.tripUuid != null && it.eucstatsStatus in setOf(0, 1, 3) }
        .sortedByDescending { it.startTime }
    override suspend fun getHeldEucstatsTrips(limit: Int) = rows.value
        .filter { it.tripUuid != null && it.eucstatsStatus == 2 && it.eucstatsValidation == "flagged" }
        .sortedByDescending { it.startTime }.take(limit)
    override suspend fun resetUnfinishedEucstatsStatuses() =
        edit({ it.eucstatsStatus in setOf(1, 3) }) { it.copy(eucstatsStatus = 0) }
    override suspend fun resetAllEucstatsStatuses() =
        edit({ it.eucstatsStatus != 0 }) { it.copy(eucstatsStatus = 0) }
}

/** The platform side of the sync, recording what the pass asked of it. No backup folder. */
class RecordingHost : DropboxTripHost {
    val retries = mutableListOf<Pair<Int, Long?>>()
    var cancels = 0
    override fun scheduleRetry(attempt: Int, delaySeconds: Long?) { retries += attempt to delaySeconds }
    override fun cancelRetry() { cancels++ }
    override suspend fun archiveInBackupFolder(settings: AppSettings, fileName: String) = true
    override suspend fun countFolderConflicts(settings: AppSettings) = 0
    override suspend fun syncFolders(settings: AppSettings, choice: SyncChoice) = 0
    override suspend fun mirrorFoldersInBackground(settings: AppSettings) = FolderMirror(0, false)
    override suspend fun snapshotBeforeRestore() = Unit
    override fun enqueueFolderUpload(settings: AppSettings) = Unit
}

/** The foreground sync's screen: answers the conflict question with [answer]. */
class ScriptedUi(var answer: SyncChoice = SyncChoice.IGNORE) : DropboxSyncUi {
    val asked = mutableListOf<Int>()
    val progress = mutableListOf<Pair<Int, Int>>()
    var result: SyncResult? = null
    override suspend fun askConflict(count: Int, firstSettings: Boolean): SyncChoice {
        asked += count
        return answer
    }
    override fun progress(done: Int, total: Int) { progress += done to total }
    override fun result(result: SyncResult) { this.result = result }
}

/**
 * One phone linked to [server]: its settings, its trip rows, its trips folder
 * and the shared sync, wired as both apps wire them. [files] and [dao] can be
 * swapped for a platform's real ones (the iOS test does).
 */
class SyncTestPhone(
    val server: FakeDropbox,
    val dao: TripDao = MemoryTripDao(),
    filesFactory: ((() -> Long) -> TripFileStore)? = null,
) {
    /** The phone's own clock, in ms, a little behind the server's. */
    var nowMs: Long = (server.nowSec - 30) * 1000L

    val files: TripFileStore = filesFactory?.invoke { nowMs } ?: MemoryTripFiles { nowMs }
    val settings = SettingsRepository(SettingsStore(MemoryDataStore()) { false })
    private val alarms = object : AlarmRuleStore {
        var rules: List<AlarmRule> = emptyList()
        override suspend fun getAll() = rules
        override suspend fun replaceAll(rules: List<AlarmRule>) { this.rules = rules }
    }
    val dropbox = DropboxRepository(settings, object : DropboxLinkScratch {
        override var verifier: String? = null
    }, transport = server)
    val dropboxSettings = DropboxSettingsSync(settings, dropbox, alarms, applyLanguage = {})
    val host = RecordingHost()
    val sync = DropboxTripSync(settings, dao, dropbox, dropboxSettings, files, host)

    suspend fun link() = settings.update {
        it.copy(
            dropboxAccessToken = "token",
            dropboxAccessTokenExpiresAt = 4_000_000_000_000L,
            dropboxAccountLabel = "rider@example.com",
        )
    }

    /** A trip recorded here: its CSV and its finished row, as the recorder leaves them. */
    suspend fun record(name: String, csv: String): TripRecord {
        nowMs += 60_000
        files.writeWhole(name, csv.encodeToByteArray())
        val row = TripRecord(fileName = name, startTime = nowMs - 60_000, endTime = nowMs, distanceKm = 1f)
        return row.copy(id = dao.insert(row))
    }

    /** Rewrite a trip here, the way a rename does: new bytes, this phone's time. */
    fun edit(name: String, csv: String) {
        nowMs += 60_000
        files.writeWhole(name, csv.encodeToByteArray())
    }

    suspend fun background(attempt: Int = 0) =
        sync.backgroundPass(attempt, isStopped = { false }, foregroundRunning = { false })

    suspend fun syncAll(ui: ScriptedUi = ScriptedUi()): ScriptedUi {
        sync.syncAll(ui)
        return ui
    }

    fun fileText(name: String): String? =
        files.files().firstOrNull { it.name == name }?.readBytes()?.decodeToString()
}
