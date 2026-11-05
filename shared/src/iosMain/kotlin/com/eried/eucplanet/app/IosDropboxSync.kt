package com.eried.eucplanet.app

import com.eried.eucplanet.data.sync.SettingsSyncAction
import com.eried.eucplanet.data.sync.SyncChoice
import com.eried.eucplanet.data.sync.syncJsonFolder
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.System
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The iOS "Sync now": settings.json (settings and alarm rules) both ways
 * through [com.eried.eucplanet.data.sync.DropboxSettingsSync], with the same
 * conflict question as Android, and the saved themes. Trips join once their
 * storage is on iOS (docs/IOS_PARITY.md).
 */
object IosDropboxSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var choice: CompletableDeferred<SyncChoice>? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Shown while the rider decides: settings changed on both sides. */
    private val _conflict = MutableStateFlow(false)
    val conflict: StateFlow<Boolean> = _conflict.asStateFlow()

    private val _firstSettings = MutableStateFlow(false)
    val firstSettings: StateFlow<Boolean> = _firstSettings.asStateFlow()

    fun syncNow() {
        if (_running.value) return
        job = scope.launch {
            _running.value = true
            try {
                val g = IosAppGraph
                val settings = g.settings.get()
                if (settings.dropboxAccessToken.isBlank()) return@launch
                g.dropbox.clearRateLimited()
                val plan = g.dropboxSettings.plan(settings)
                var action = plan.action
                if (plan.conflict) {
                    val d = CompletableDeferred<SyncChoice>()
                    choice = d
                    _firstSettings.value = settings.dropboxSettingsBaseHash.isEmpty()
                    _conflict.value = true
                    val picked = d.await()
                    _conflict.value = false
                    choice = null
                    if (picked == SyncChoice.CANCEL) return@launch
                    action = when (picked) {
                        SyncChoice.APP -> SettingsSyncAction.UPLOAD
                        SyncChoice.FOLDER -> SettingsSyncAction.APPLY
                        else -> SettingsSyncAction.NONE
                    }
                }
                val outcome = g.dropboxSettings.carryOut(plan, action)
                // Saved themes, both ways, as Android mirrors its backup folder's
                // themes/. A theme changed on both sides follows the rider's answer
                // above when there was one, and is left alone otherwise.
                val themeChoice = when (action) {
                    SettingsSyncAction.UPLOAD -> if (plan.conflict) SyncChoice.APP else SyncChoice.IGNORE
                    SettingsSyncAction.APPLY -> if (plan.conflict) SyncChoice.FOLDER else SyncChoice.IGNORE
                    else -> SyncChoice.IGNORE
                }
                syncJsonFolder(g.dropbox, "themes", g.themeFiles, themeChoice)
                if (!outcome.failed) {
                    g.settings.update { it.copy(dropboxLastSyncAt = System.currentTimeMillis()) }
                }
            } catch (e: Exception) {
                Log.w("IosDropboxSync", "sync failed: ${e.message}")
            } finally {
                _conflict.value = false
                _running.value = false
            }
        }
    }

    fun resolve(picked: SyncChoice) {
        choice?.complete(picked)
    }

    fun stop() {
        choice?.complete(SyncChoice.CANCEL)
        job?.cancel()
    }

    fun link() {
        scope.launch {
            if (IosDropboxLink.link(IosAppGraph.dropbox)) syncNow()
        }
    }

    fun unlink() {
        scope.launch { IosAppGraph.dropbox.unlink() }
    }
}
