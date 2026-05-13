package com.eried.eucplanet.wear

import android.util.Log
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.flic.FlicManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives `/euc/control` messages sent from the watch and dispatches them to
 * [WheelRepository]. The watch never talks to the wheel directly; it asks the
 * phone, the phone enqueues the BLE write, the wheel responds, telemetry
 * picks up the new state and gets pushed back via [WearBridge].
 *
 * Wear OS auto-creates this service when a message lands at the matching path.
 * Hilt's @AndroidEntryPoint injects the repository singleton so the call goes
 * through the same code path the phone UI uses.
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "PhoneWear"
        private const val PATH_CONTROL = "/euc/control"
        private const val PATH_WATCH_INFO = "/euc/watch_info"

        private const val CMD_HORN = "horn"
        private const val CMD_LIGHT_ON = "light_on"
        private const val CMD_LIGHT_OFF = "light_off"
        private const val ACTION_PREFIX = "action:"
    }

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var flicManager: FlicManager

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_CONTROL -> handleControl(String(event.data))
            PATH_WATCH_INFO -> {
                // Watch sends this once per launch with its own Build /
                // BuildConfig info so the Service Mode log captures both
                // sides of a paired session. Only logged when the diagnostic
                // logger is enabled — no-op otherwise.
                val info = String(event.data)
                Log.i(TAG, "watch info: $info")
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.info("watch: $info")
            }
        }
    }

    private fun handleControl(command: String) {
        Log.i(TAG, "control from watch: $command")
        when {
            command == CMD_HORN -> wheelRepository.sendHorn()
            command == CMD_LIGHT_ON || command == CMD_LIGHT_OFF ->
                wheelRepository.toggleLight()
            command.startsWith(ACTION_PREFIX) ->
                flicManager.dispatchActionByName(command.removePrefix(ACTION_PREFIX))
            else -> Log.w(TAG, "unknown control: $command")
        }
    }
}
