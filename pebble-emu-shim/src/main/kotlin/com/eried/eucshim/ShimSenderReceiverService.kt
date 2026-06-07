package com.eried.eucshim

import android.util.Log
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleSenderReceiver
import java.util.UUID

/**
 * The fake CoreApp's receiving end. PebbleKitAndroid2's `DefaultPebbleSender`
 * (inside the EUC app) binds to this service via the
 * `io.rebble.pebblekit2.SEND_DATA_TO_WATCH` action and calls
 * [sendDataToPebble] for every telemetry frame. We forward the dict into the
 * QEMU emulator ([EmuForwarder]) and report Success, exactly as the real
 * CoreApp would after delivering over BLE.
 */
class ShimSenderReceiverService : BasePebbleSenderReceiver() {

    override suspend fun sendDataToPebble(
        callingPackage: String?,
        watchappUUID: UUID,
        data: PebbleDictionary,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> {
        EmuForwarder.send(data)
        Log.i(TAG, "telemetry from ${callingPackage ?: "?"}: ${data.size} keys -> emulator")
        return mapOf(SHIM_WATCH to TransmissionResult.Success)
    }

    override suspend fun startAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> = mapOf(SHIM_WATCH to TransmissionResult.Success)

    override suspend fun stopAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?,
    ): Map<WatchIdentifier, TransmissionResult> = mapOf(SHIM_WATCH to TransmissionResult.Success)

    companion object {
        const val TAG = "EucPebbleShim"
        /** The fake watch the EUC app sees. Name is cosmetic. */
        val SHIM_WATCH = WatchIdentifier("emu-emery")
    }
}
