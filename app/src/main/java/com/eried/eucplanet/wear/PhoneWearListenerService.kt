package com.eried.eucplanet.wear

import android.util.Log
import com.eried.eucplanet.data.repository.WheelRepository
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

        private const val CMD_HORN = "horn"
        private const val CMD_LIGHT_ON = "light_on"
        private const val CMD_LIGHT_OFF = "light_off"
    }

    @Inject lateinit var wheelRepository: WheelRepository

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_CONTROL) return
        val command = String(event.data)
        Log.i(TAG, "control from watch: $command")
        when (command) {
            CMD_HORN -> wheelRepository.sendHorn()
            CMD_LIGHT_ON, CMD_LIGHT_OFF -> wheelRepository.toggleLight()
            else -> Log.w(TAG, "unknown control: $command")
        }
    }
}
