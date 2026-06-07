package com.eried.eucshim

import android.util.Log
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Socket

/**
 * Forwards each telemetry dict to the WSL-side `emu-bridge.py`, which injects it
 * into the running emery emulator via `pebble send-app-message`.
 *
 * Transport: a fresh TCP connection per frame to [HOST]:[PORT]. On the Android
 * emulator, point that at the bridge with:
 *   adb -s emulator-5588 reverse tcp:5599 tcp:5599
 * so device-localhost:5599 reaches the host, which WSL forwards to the bridge.
 *
 * Keys are tagged by type so the bridge can rebuild the send-app-message args:
 * `i<key>` for integers (Int32 etc.), `s<key>` for strings (the unit codes).
 */
object EmuForwarder {
    private const val HOST = "127.0.0.1"
    private const val PORT = 5599
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(data: PebbleDictionary) {
        val json = JSONObject()
        for ((key, item) in data) {
            when (item) {
                is PebbleDictionaryItem.Text -> json.put("s$key", item.value)
                is PebbleDictionaryItem.Int8 -> json.put("i$key", item.value.toInt())
                is PebbleDictionaryItem.Int16 -> json.put("i$key", item.value.toInt())
                is PebbleDictionaryItem.Int32 -> json.put("i$key", item.value)
                is PebbleDictionaryItem.UInt8 -> json.put("i$key", item.value.toInt())
                is PebbleDictionaryItem.UInt16 -> json.put("i$key", item.value.toInt())
                is PebbleDictionaryItem.UInt32 -> json.put("i$key", item.value.toLong())
                is PebbleDictionaryItem.Bytes -> Unit // not used by EUC telemetry
            }
        }
        val line = json.toString() + "\n"
        scope.launch {
            runCatching {
                Socket(HOST, PORT).use { sock ->
                    sock.getOutputStream().apply { write(line.toByteArray()); flush() }
                }
            }.onFailure { Log.w(ShimSenderReceiverService.TAG, "forward failed: ${it.message}") }
        }
    }
}
