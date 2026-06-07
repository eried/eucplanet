package com.eried.eucplanet.pebble

import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * Hilt accessor for the singleton [PebbleBridge].
 *
 * Deliberately a TOP-LEVEL interface, not nested inside [PebbleListenerService].
 * Hilt's generated `SingletonC` component `implements` this entry point, and
 * javac must be able to load the enclosing type. [PebbleListenerService]
 * extends pebblekit2's [BasePebbleListenerService], which is Java-21
 * (class-file version 65) bytecode that javac-17 cannot read; nesting the entry
 * point there would drag that unreadable superclass into the generated Java
 * component and break the Java compile. Keeping it top-level means only the
 * Kotlin compiler ever touches the SDK base class.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PebbleBridgeEntryPoint {
    fun pebbleBridge(): PebbleBridge
}

/**
 * Receives watchapp lifecycle (and, later, inbound button) events from the
 * Pebble companion. PebbleKitAndroid2 binds this via its
 * `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent action (declared in
 * `src/pebbleEnabled/AndroidManifest.xml`).
 *
 * This cut is telemetry-only: we use ONLY [onAppOpened] / [onAppClosed] to
 * gate the [PebbleBridge] telemetry pump, so the phone pushes frames only while
 * the watchapp is on-screen. Inbound message handling (button actions) is a
 * deferred follow-up, exactly the staging the Wear OS and Garmin companions
 * went through.
 *
 * Reaches the singleton [PebbleBridge] via a Hilt [EntryPoint] accessor rather
 * than `@AndroidEntryPoint`, so Hilt never generates a Java subclass of the
 * Java-21 SDK base class (see [PebbleBridgeEntryPoint]).
 */
class PebbleListenerService : BasePebbleListenerService() {

    companion object {
        private const val TAG = "PebbleListener"
    }

    private val pebbleBridge: PebbleBridge by lazy {
        EntryPointAccessors
            .fromApplication(applicationContext, PebbleBridgeEntryPoint::class.java)
            .pebbleBridge()
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.i(TAG, "watchapp opened: $watchappUUID on ${watch.value}")
        pebbleBridge.onWatchAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.i(TAG, "watchapp closed: $watchappUUID on ${watch.value}")
        pebbleBridge.onWatchAppClosed()
    }
}
