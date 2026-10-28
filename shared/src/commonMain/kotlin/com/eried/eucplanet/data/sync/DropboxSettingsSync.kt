package com.eried.eucplanet.data.sync

import com.eried.eucplanet.data.model.AlarmRule
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.DropboxRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.store.AlarmBackupJson
import com.eried.eucplanet.data.store.SettingsJson
import com.eried.eucplanet.platform.Charsets
import com.eried.eucplanet.platform.JSONObject
import com.eried.eucplanet.platform.String
import com.eried.eucplanet.platform.toByteArray
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.sha256

/** The alarm rules settings.json carries: Room's AlarmDao on Android. */
interface AlarmRuleStore {
    suspend fun getAll(): List<AlarmRule>

    /** Replace every rule, each inserted as a new row. */
    suspend fun replaceAll(rules: List<AlarmRule>)
}

/**
 * Dropbox's `/settings.json`, the same file on every phone: the portable
 * settings plus the alarm rules, compared three ways against the copy both
 * sides last agreed on ([SettingsSyncPolicy]). SyncManager runs it on Android
 * inside its whole-library pass; iOS runs it on its own.
 */
class DropboxSettingsSync(
    private val settingsRepository: SettingsRepository,
    private val dropboxRepository: DropboxRepository,
    private val alarms: AlarmRuleStore,
    /** Puts a restored language into effect, not just into the settings row. */
    private val applyLanguage: suspend (String) -> Unit,
) {
    /**
     * What goes to Dropbox as settings.json, and what both sides' fingerprints
     * are taken over: the portable settings plus the alarm rules, the same
     * shape as the backup-folder copy, so alarms travel too.
     */
    suspend fun portableSettingsBytes(s: AppSettings): ByteArray =
        SettingsJson.toJson(SettingsJson.stripDeviceBindings(s)).apply {
            put("alarms", AlarmBackupJson.alarmsToJson(alarms.getAll()))
        }.toString().toByteArray(Charsets.UTF_8)

    /**
     * Dropbox's settings.json. [known] is false when it cannot be told whether
     * the file exists: a download returns null for a missing file and for a
     * network blip alike, and treating a blip as "missing" uploaded this phone
     * over the rider's real settings. A listing settles it.
     */
    class RemoteSettings(val known: Boolean, val bytes: ByteArray?)

    suspend fun readRemoteSettings(): RemoteSettings {
        dropboxRepository.downloadFile("/settings.json")?.let { return RemoteSettings(true, it) }
        val root = dropboxRepository.listFolder("") ?: return RemoteSettings(false, null)
        val present = root.keys.any { it.equals("settings.json", ignoreCase = true) }
        return RemoteSettings(known = !present, bytes = null)
    }

    /**
     * The background half of the settings sync: push this phone's settings only
     * when Dropbox's copy has not moved since the last sync, so another phone's
     * edit is never overwritten without the prompt. A Dropbox-side change is left
     * for the foreground sync, which can ask the rider. Returns false only when
     * an upload was due and failed.
     */
    suspend fun pushIfOnlyPhoneChanged(): Boolean {
        val settings = settingsRepository.get()
        val remote = readRemoteSettings()
        if (!remote.known) return true
        val payload = portableSettingsBytes(settings)
        val phoneHash = sha256Hex(payload)
        return when (SettingsSyncPolicy.decide(
            phoneHash, remote.bytes?.let { sha256Hex(it) }, settings.dropboxSettingsBaseHash
        )) {
            SettingsSyncAction.UPLOAD ->
                dropboxRepository.uploadFile("/settings.json", payload).also { ok ->
                    if (ok) settingsRepository.update { it.copy(dropboxSettingsBaseHash = phoneHash) }
                }
            SettingsSyncAction.NONE -> {
                settingsRepository.update { it.copy(dropboxSettingsBaseHash = phoneHash) }
                true
            }
            SettingsSyncAction.APPLY, SettingsSyncAction.CONFLICT -> true
        }
    }

    /** Both sides of one foreground pass, decided before anything is written. */
    class Plan(
        val phoneBytes: ByteArray,
        val phoneHash: String,
        val remoteBytes: ByteArray?,
        val remoteHash: String?,
        val action: SettingsSyncAction,
    ) {
        val conflict: Boolean get() = action == SettingsSyncAction.CONFLICT
    }

    // --- Settings (whole-blob, 3-way vs the stored baseline) ---
    suspend fun plan(settings: AppSettings): Plan {
        val phoneSettingsBytes = portableSettingsBytes(settings)
        val phoneSettingsHash = sha256Hex(phoneSettingsBytes)
        val remoteSettings = readRemoteSettings()
        val remoteSettingsBytes = remoteSettings.bytes
        val remoteSettingsHash = remoteSettingsBytes?.let { sha256Hex(it) }
        val settingsAction = if (!remoteSettings.known) {
            // Could not tell what Dropbox holds: leave settings for the next pass.
            SettingsSyncAction.NONE
        } else {
            SettingsSyncPolicy.decide(
                phoneSettingsHash, remoteSettingsHash, settings.dropboxSettingsBaseHash
            )
        }
        return Plan(phoneSettingsBytes, phoneSettingsHash, remoteSettingsBytes, remoteSettingsHash, settingsAction)
    }

    /** What [carryOut] did: [changed] when a file moved either way, [failed] when one should have and did not. */
    class Outcome(val changed: Boolean, val failed: Boolean)

    /**
     * Do [action] (the plan's own, or what the rider picked for a conflict).
     * [beforeApply] runs before Dropbox's copy replaces this phone's, for an
     * undo point.
     */
    suspend fun carryOut(plan: Plan, action: SettingsSyncAction, beforeApply: suspend () -> Unit = {}): Outcome {
        var changed = false
        var failed = false
        when (action) {
            SettingsSyncAction.UPLOAD -> {
                if (dropboxRepository.uploadFile("/settings.json", plan.phoneBytes)) {
                    changed = true
                    settingsRepository.update { it.copy(dropboxSettingsBaseHash = plan.phoneHash) }
                } else failed = true
            }
            SettingsSyncAction.APPLY -> {
                if (plan.remoteBytes != null) {
                    beforeApply()
                    if (applySettingsFromJson(plan.remoteBytes)) {
                        changed = true
                        settingsRepository.update {
                            it.copy(dropboxSettingsBaseHash = plan.remoteHash ?: it.dropboxSettingsBaseHash)
                        }
                    } else {
                        // Unreadable on Dropbox. Not "up to date": the pass
                        // reports a failure and the baseline stays put.
                        Log.w(TAG, "Dropbox settings.json could not be applied")
                        failed = true
                    }
                }
            }
            SettingsSyncAction.NONE -> {
                // Already in sync (or skipped). Advance the baseline so we do not
                // re-flag the same state next pass.
                if (plan.remoteHash == plan.phoneHash)
                    settingsRepository.update { it.copy(dropboxSettingsBaseHash = plan.phoneHash) }
            }
            SettingsSyncAction.CONFLICT -> { /* resolved into UPLOAD/APPLY/NONE by the caller */ }
        }
        return Outcome(changed, failed)
    }

    /**
     * Apply a settings JSON (from a backup file or a Dropbox download) to this
     * phone via [SettingsJson.applyPortable], so the phone keeps its own device +
     * sync bindings. Re-applies language and, if the JSON carries an "alarms"
     * array, replaces the alarm rules. Caller takes any snapshot first.
     */
    suspend fun applySettingsFromJson(bytes: ByteArray): Boolean {
        val current = settingsRepository.get()
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val restored = SettingsJson.applyPortable(json, current)
            settingsRepository.update(restored)
            applyLanguage(restored.language)
            // Replace alarm rules wholesale only if the backup contains an
            // "alarms" array. Older backups (pre-v0.4.3) keep the user's
            // current rules untouched.
            if (json.has("alarms")) {
                alarms.replaceAll(AlarmBackupJson.jsonToAlarms(json.optJSONArray("alarms")))
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Settings apply failed", e)
            false
        }
    }

    companion object {
        // Logs keep the tag they had inside SyncManager.
        private const val TAG = "SyncManager"

        fun sha256Hex(bytes: ByteArray): String =
            sha256(bytes).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
