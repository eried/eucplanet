package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.db.TripDao
import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.repository.DropboxLinkScratch
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.SettingsStore

/**
 * A backup folder in memory, as a document provider or the Files app holds
 * one: folders, files with their bytes and times, names matched exactly.
 * [renameClashes] makes a create of a name that differs only in case come
 * out as "name (1).csv", the way Android's providers rename a clash, so the
 * case-insensitive lookups can be tested.
 */
class MemoryBackupFolder(
    private val clock: () -> Long = { 1_787_000_000_000L },
    var renameClashes: Boolean = false,
) : BackupFolder {
    class Stored(var bytes: ByteArray, var modifiedMs: Long)

    val files = LinkedHashMap<String, Stored>()
    val dirs = linkedSetOf("")

    /** A storage-side move the folder refuses, so the copy fallback runs. */
    var refuseMoves = false
    var moves = 0

    override val displayName: String = "Memory folder"

    private fun parent(path: String) = path.substringBeforeLast('/', "")
    private fun name(path: String) = path.substringAfterLast('/')

    fun text(path: String): String? = files[path]?.bytes?.decodeToString()

    fun put(path: String, text: String) {
        var dir = parent(path)
        while (true) {
            dirs += dir
            if (dir.isEmpty()) break
            dir = parent(dir)
        }
        files[path] = Stored(text.encodeToByteArray(), clock())
    }

    fun names(dir: String): List<String> = list(dir)?.map { it.name }.orEmpty()

    override fun list(dir: String): List<BackupFolderEntry>? {
        if (dir !in dirs) return null
        val subdirs = dirs.filter { it.isNotEmpty() && parent(it) == dir }
            .map { BackupFolderEntry(name(it), true, 0L, 0L) }
        val inside = files.filterKeys { parent(it) == dir }
            .map { (p, s) -> BackupFolderEntry(name(p), false, s.bytes.size.toLong(), s.modifiedMs) }
        return subdirs + inside
    }

    override fun read(path: String): ByteArray? = files[path]?.bytes?.copyOf()

    override fun write(path: String, bytes: ByteArray): Boolean {
        if (parent(path) !in dirs || path in dirs) return false
        var target = path
        if (renameClashes && path !in files) {
            val clash = files.keys.any { parent(it) == parent(path) && it.equals(path, ignoreCase = true) }
            if (clash) {
                val stem = path.substringBeforeLast('.')
                val ext = path.substringAfterLast('.', "")
                var n = 1
                while ("$stem ($n).$ext" in files) n++
                target = "$stem ($n).$ext"
            }
        }
        files[target] = Stored(bytes.copyOf(), clock())
        return true
    }

    override fun delete(path: String): Boolean {
        if (files.remove(path) != null) return true
        if (path.isNotEmpty() && path in dirs) {
            dirs.removeAll { it == path || it.startsWith("$path/") }
            files.keys.removeAll { it.startsWith("$path/") }
            return true
        }
        return false
    }

    override fun ensureDirectory(path: String): Boolean {
        if (path in files) return false
        var dir = path
        while (dir.isNotEmpty()) {
            if (dir in files) return false
            dirs += dir
            dir = parent(dir)
        }
        return true
    }

    override fun moveInto(path: String, toDir: String): Boolean {
        if (refuseMoves || toDir !in dirs) return false
        val dest = folderPath(toDir, name(path))
        if (dest in files) return false
        val s = files.remove(path) ?: return false
        files[dest] = s
        moves++
        return true
    }
}

/** The alarm rules in memory. */
class MemoryAlarms : AlarmRuleStore {
    var rules: List<AlarmRule> = emptyList()
    override suspend fun getAll() = rules
    override suspend fun replaceAll(rules: List<AlarmRule>) { this.rules = rules }
}

/** What [BackupFolderSync] asked of its platform. */
class RecordingFolderHost : BackupFolderHost {
    val scheduled = mutableListOf<Int>()
    var cancels = 0
    override fun scheduleUpload(attempt: Int) { scheduled += attempt }
    override fun cancelUpload() { cancels++ }
}

/**
 * One phone with a backup folder: its settings, trip rows and trips folder,
 * and the shared folder sync wired as both apps wire it. Two of these on one
 * [folder] are an Android phone and an iPhone sharing a folder. [files] and
 * [dao] can be the platform's real ones (the iOS test does).
 */
class FolderTestPhone(
    val folder: BackupFolder = MemoryBackupFolder(),
    val dao: TripDao = MemoryTripDao(),
    filesFactory: ((() -> Long) -> TripFileStore)? = null,
    val server: FakeDropbox = FakeDropbox(),
) {
    var nowMs: Long = 1_787_000_000_000L

    val files: TripFileStore = filesFactory?.invoke { nowMs } ?: MemoryTripFiles { nowMs }
    val settings = SettingsRepository(SettingsStore(MemoryDataStore(), startingUnits = { com.eried.eucplanet.data.store.DefaultUnits.METRIC }))
    val alarms = MemoryAlarms()
    val dropbox = DropboxRepository(settings, object : DropboxLinkScratch {
        override var verifier: String? = null
    }, transport = server)
    val dropboxSettings = DropboxSettingsSync(settings, dropbox, alarms, applyLanguage = {})
    val host = RecordingFolderHost()

    /** The folder the settings name; a phone whose folder is gone answers null. */
    var reachable = true

    val sync = BackupFolderSync(
        settings, dao, dropbox, dropboxSettings, alarms, files,
        access = { uri -> if (reachable && uri == URI) folder else null },
        host = host,
    )

    suspend fun pickFolder() = sync.useFolder(URI)

    suspend fun record(name: String, csv: String, status: Int = 1): com.eried.eucplanet.data.model.TripRecord {
        nowMs += 60_000
        files.writeWhole(name, csv.encodeToByteArray())
        val row = com.eried.eucplanet.data.model.TripRecord(
            fileName = name, startTime = nowMs - 60_000, endTime = nowMs, distanceKm = 1f, uploadStatus = status,
        )
        return row.copy(id = dao.insert(row))
    }

    companion object {
        const val URI = "memory:backup"
    }
}
