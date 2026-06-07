package com.eried.eucshim

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.DefaultPebbleListenerConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Tiny control panel for the fake Pebble app.
 *
 * "Connect" tells the EUC app's listener service that the watchapp opened
 * (`sendOnAppOpened`) — that flips the EUC app's Paired-devices card to active
 * and starts its telemetry pump, which then streams frames to
 * [ShimSenderReceiverService] → the emulator. "Disconnect" sends the matching
 * app-closed event.
 */
class ShimActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val connector by lazy {
        DefaultPebbleListenerConnector(applicationContext, listOf(EUC_PKG))
    }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        val title = TextView(this).apply {
            text = "EUC Pebble Shim"
            textSize = 22f
        }
        status = TextView(this).apply {
            text = "Idle. Open the watchapp (Connect) so the EUC app starts sending."
            textSize = 15f
            setPadding(0, 32, 0, 32)
        }
        val connect = Button(this).apply {
            text = "Connect — open watchapp"
            setOnClickListener { open() }
        }
        val disconnect = Button(this).apply {
            text = "Disconnect — close watchapp"
            setOnClickListener { close() }
        }
        root.addView(title)
        root.addView(status)
        root.addView(connect)
        root.addView(disconnect)
        setContentView(root)

        // Scriptable for the emulator demo / tester handoff:
        //   adb shell am start -n com.eried.eucshim/.ShimActivity --ez autoconnect true
        val auto = intent?.getBooleanExtra("autoconnect", false) == true
        Log.i(TAG, "onCreate autoconnect=$auto")
        if (auto) open()
    }

    private fun open() = scope.launch {
        Log.i(TAG, "open(): sendOnAppOpened -> $EUC_PKG")
        val ok = withContext(Dispatchers.IO) {
            runCatching { connector.sendOnAppOpened(WATCHAPP_UUID, SHIM_WATCH) }
                .onFailure { Log.e(TAG, "sendOnAppOpened threw", it) }
                .getOrDefault(false)
        }
        Log.i(TAG, "open(): result=$ok")
        status.text = if (ok) {
            "Connected. EUC app should show Pebble (active) and stream telemetry to the emulator."
        } else {
            "Couldn't reach the EUC app. Is com.eried.eucplanet installed with Pebble enabled?"
        }
    }

    private fun close() = scope.launch {
        withContext(Dispatchers.IO) {
            runCatching { connector.sendOnAppClosed(WATCHAPP_UUID, SHIM_WATCH) }
        }
        status.text = "Disconnected (watchapp closed)."
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { connector.close() }
    }

    companion object {
        private const val TAG = "EucPebbleShim"
        private const val EUC_PKG = "com.eried.eucplanet"
        // Must match pebble-watch-app/package.json + PebbleBridge.PEBBLE_APP_UUID.
        private val WATCHAPP_UUID = UUID.fromString("71cc8578-8aad-4179-8d5c-98bb0b13c2e1")
        private val SHIM_WATCH = WatchIdentifier("emu-emery")
    }
}
