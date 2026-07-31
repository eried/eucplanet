package com.eried.eucplanet.ble.gps

import com.eried.eucplanet.data.model.ExternalGpsSample
import com.eried.eucplanet.data.model.ExternalGpsSource
import java.util.UUID

/**
 * GATT service + characteristic UUIDs an external-GPS box binds to. Most boxes
 * (RaceBox) expose Nordic UART, but some use a custom profile: the real Dragy
 * uses service 0xFD00 with notify 0xFD02 and command-write 0xFD01. The
 * connection manager reads this from the active adapter on service discovery.
 */
data class ExternalGpsGattProfile(
    val serviceUuid: UUID,
    /** Characteristic the device streams telemetry on (notify). */
    val notifyUuid: UUID,
    /** Characteristic the adapter writes commands / init to. */
    val writeUuid: UUID
) {
    companion object {
        private fun uuid16(short: String): UUID =
            UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

        /** Nordic UART: service 6e40…0001, notify (TX) …0003, write (RX) …0002. */
        val NORDIC_UART = ExternalGpsGattProfile(
            serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            notifyUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            writeUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        )

        /** Dragy: eWheel-style custom profile. Service FD00, notify FD02
         *  (GPS data), command-write FD01. Confirmed from the official Dragy
         *  app + a tester GATT dump (2026-07-20). */
        val DRAGY = ExternalGpsGattProfile(
            serviceUuid = uuid16("fd00"),
            notifyUuid = uuid16("fd02"),
            writeUuid = uuid16("fd01")
        )
    }
}

/**
 * Per-vendor adapter for an external BLE GPS box. Each implementation owns:
 *  * advertisement matching (so the scanner can list only its own kind),
 *  * the GATT profile it binds to (Nordic UART by default),
 *  * decoding of the raw notification stream into [ExternalGpsSample]s.
 *
 * The architecture intentionally mirrors [com.eried.eucplanet.ble.WheelAdapter]:
 * one interface, one implementation per family, registered into a Hilt set so
 * the scanner / connection manager iterate them without knowing the concrete
 * types. Ships [RaceBoxAdapter] + [DragyAdapter]; future VBox / Garmin
 * Catalyst adapters slot in by adding a [Binds] entry.
 */
interface ExternalGpsAdapter {
    val source: ExternalGpsSource

    /** Match the device by its advertised name. Called for every BLE scan result. */
    fun matches(deviceName: String): Boolean

    /** GATT service + characteristics to bind to. Default Nordic UART (RaceBox);
     *  a custom-profile box (Dragy) overrides. */
    fun gattProfile(): ExternalGpsGattProfile = ExternalGpsGattProfile.NORDIC_UART

    /**
     * Decode one BLE notification frame into a sample. Returns null when the
     * frame is partial (mid-reassembly), unrecognised, or just not a sample
     * frame (some devices interleave status frames). Stateful adapters keep
     * their reassembly buffer internally; the connection manager forwards
     * raw bytes verbatim.
     */
    fun decode(notification: ByteArray): ExternalGpsSample?

    /**
     * Bytes the connection manager should write to the RX characteristic
     * right after the TX notification subscription is established. For
     * RaceBox this is MGA-INI-TIME_UTC + MGA-INI-POS_LLH, mirroring the
     * official RaceBox app's post-connect handshake (captured 2026-05-13
     * via btsnoop). Without these, the GNSS does a full cold-start search
     * and a fix can take 30–90 s.
     *
     * @param timeUtcMillis  current UTC wall clock from the phone
     * @param lastKnownLat   most recent phone GPS latitude in degrees, or
     *                       null if no location is available; adapters
     *                       MAY still emit a time-only init in that case.
     * @param lastKnownLon   longitude paired with [lastKnownLat]
     * @param lastKnownAccM  horizontal accuracy of the position in metres
     */
    fun initCommands(
        timeUtcMillis: Long,
        lastKnownLat: Double?,
        lastKnownLon: Double?,
        lastKnownAccM: Float?
    ): List<ByteArray> = emptyList()

    /**
     * A characteristic the connection manager should READ on a fixed interval,
     * or null (the default) when the box streams everything it needs to on the
     * notify channel. Used for values that live off the telemetry stream - e.g.
     * Dragy exposes its battery on a device-status characteristic that has to
     * be read, not subscribed. The read runs only after the post-connect init
     * writes drain, so it never overlaps another GATT op. Results arrive at
     * [onPollResult]; RaceBox leaves this null because its battery byte rides
     * inside the streamed frame.
     */
    fun pollCharacteristic(): UUID? = null

    /** How often to re-read [pollCharacteristic]. Ignored when it is null. */
    fun pollIntervalMs(): Long = 30_000L

    /**
     * Result bytes of a [pollCharacteristic] read. The adapter folds the value
     * into its own state so the next [decode] sample carries it (battery is
     * slow-moving, so riding along with the position samples is fine). Default:
     * ignore.
     */
    fun onPollResult(value: ByteArray) {}
}
