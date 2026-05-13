package com.eried.eucplanet.wear.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide cache of the latest [WatchState] pushed by the phone, plus
 * helpers for sending control intents back. Held as a singleton object so
 * both the listener service (background) and the Activity (foreground) read
 * and write the same flow without DI.
 *
 * Network/Bluetooth calls go through MessageClient on the IO dispatcher so
 * the UI thread isn't blocked when the user taps Horn.
 */
object WatchStateRepository {

    private const val TAG = "WatchState"

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    /**
     * Wall-clock time of the last [update] call, in millis. Used by the UI to
     * tell apart the "phone hasn't said hi yet" placeholder from the
     * "phone is here, just no wheel" one. Zero until the first push lands.
     */
    private val _lastPushAtMs = MutableStateFlow(0L)
    val lastPushAtMs: StateFlow<Long> = _lastPushAtMs.asStateFlow()

    /**
     * Fires when the phone asks the watch app to close (Stop all flow on the
     * phone, with the user's "Close watch on exit" preference enabled). The
     * Activity collects it and calls finishAndRemoveTask so the dial doesn't
     * sit on a stale frame after the phone tears its session down.
     */
    private val _closeSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val closeSignal: SharedFlow<Unit> = _closeSignal.asSharedFlow()

    fun requestClose() {
        _closeSignal.tryEmit(Unit)
    }

    /**
     * Tracks whether the watch's MainActivity is currently visible. Set true
     * from onStart and false from onStop so the bridge service can skip its
     * relaunch animation when the user is already looking at the dial — the
     * phone fires a /euc/wake every time the user re-opens the phone app and
     * that was causing a needless reopen flicker on the watch.
     */
    @Volatile
    var activityVisible: Boolean = false
        private set

    fun setActivityVisible(visible: Boolean) {
        activityVisible = visible
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun update(snapshot: WatchState) {
        _state.value = snapshot
        _lastPushAtMs.value = System.currentTimeMillis()
    }

    /**
     * Send a one-shot control intent to the phone. Picks the first connected
     * "phone" node (typically there's only one) and sends a Message. We don't
     * wait for ack: the phone's WearBridge has the authoritative state anyway,
     * so a dropped horn press is recoverable, and blocking the UI for 1 to 2
     * seconds on a watch tap is worse than the rare miss.
     */
    fun sendControl(context: Context, intent: String) {
        scope.launch {
            try {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                if (nodes.isEmpty()) {
                    Log.w(TAG, "No connected phone for control=$intent")
                    return@launch
                }
                val message = Wearable.getMessageClient(context)
                for (node in nodes) {
                    Tasks.await(
                        message.sendMessage(node.id, WatchPaths.CONTROL, intent.toByteArray())
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendControl($intent) failed", e)
            }
        }
    }

    /**
     * One-shot announce that runs on watch app launch: ships Build /
     * BuildConfig info to the phone so its Service Mode log captures both
     * sides of the pair. Best-effort; failures are logged at DEBUG and
     * don't propagate. Idempotent at the message level — Wearable's
     * MessageClient handles re-delivery if the phone is asleep.
     */
    private var infoSent = false
    fun sendWatchInfo(context: Context) {
        if (infoSent) return
        infoSent = true
        scope.launch {
            try {
                val info = buildString {
                    append("model=")
                    append(android.os.Build.MODEL)
                    append("|mfr=")
                    append(android.os.Build.MANUFACTURER)
                    append("|os=")
                    append(android.os.Build.VERSION.RELEASE)
                    append("|sdk=")
                    append(android.os.Build.VERSION.SDK_INT)
                    append("|app=")
                    append(com.eried.eucplanet.wear.BuildConfig.VERSION_NAME)
                    append(" (")
                    append(com.eried.eucplanet.wear.BuildConfig.VERSION_CODE)
                    append(')')
                }
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = Tasks.await(nodeClient.connectedNodes)
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No connected phone for watch_info")
                    infoSent = false
                    return@launch
                }
                val message = Wearable.getMessageClient(context)
                for (node in nodes) {
                    Tasks.await(
                        message.sendMessage(node.id, WatchPaths.WATCH_INFO, info.toByteArray())
                    )
                }
                Log.i(TAG, "watch_info sent: $info")
            } catch (e: Exception) {
                Log.d(TAG, "sendWatchInfo failed", e)
                infoSent = false
            }
        }
    }
}
