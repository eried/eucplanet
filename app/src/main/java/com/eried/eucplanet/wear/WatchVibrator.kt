package com.eried.eucplanet.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a one-shot vibrate hint to every paired watch via the Wearable
 * Message API. Used by [com.eried.eucplanet.service.AlarmEngine] when an
 * alarm rule's `vibrateTarget` is `WATCH` or `BOTH`.
 *
 * The wear side's [com.eried.eucplanet.wear.bridge.WatchBridgeService]
 * decodes the 4-byte little-endian payload as a duration in milliseconds and
 * triggers its own [android.os.Vibrator]. The phone never blocks on the
 * round trip — fire-and-forget on a coroutine, exceptions logged.
 */
@Singleton
class WatchVibrator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WatchVibrator"
        private const val PATH_VIBRATE = "/euc/vibrate"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun vibrate(durationMs: Int) {
        val ms = durationMs.coerceIn(50, 5000)
        val payload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(ms).array()
        scope.launch {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, PATH_VIBRATE, payload)
                }
            } catch (e: Exception) {
                Log.d(TAG, "watch vibrate skipped: ${e.message}")
            }
        }
    }
}
