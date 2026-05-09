package com.eried.eucplanet.wear.bridge

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
}
