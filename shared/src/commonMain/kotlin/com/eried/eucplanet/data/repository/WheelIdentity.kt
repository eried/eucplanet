package com.eried.eucplanet.data.repository

import com.eried.eucplanet.platform.JSONObject
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Accumulates the connected wheel's identity over the course of a ride.
 *
 * Model, serial and firmware live in [WheelRepository] StateFlows that are nulled the
 * moment BLE drops, and the normal way to end a ride is to power the wheel off, so
 * reading them once at stop hands back nulls, the upload carries no serial or MAC, and
 * eucstats has nothing to key the wheel on. Such a trip still counts for the rider but
 * reaches no wheel or brand board at all.
 *
 * Merging as the ride runs fixes that: once a field is known it stays known, because a
 * later blank is ignored. A real value still replaces an earlier real value (identity
 * trickles in - the MAC at connect, the serial only once the wheel answers).
 */
class WheelIdentity {
    private val lock = SynchronizedObject()
    private val fields = HashMap<String, String>()

    fun merge(
        brand: String? = null,
        model: String? = null,
        serial: String? = null,
        bleMac: String? = null,
        bleName: String? = null,
        firmware: String? = null,
    ) {
        put("brand", brand); put("model", model); put("serial", serial)
        put("ble_mac", bleMac); put("ble_name", bleName); put("firmware", firmware)
    }

    private fun put(key: String, value: String?) {
        if (!value.isNullOrBlank()) synchronized(lock) { fields[key] = value }
    }

    private fun field(key: String): String? = synchronized(lock) { fields[key] }

    fun clear() = synchronized(lock) { fields.clear() }

    /**
     * The identity as CSV Extra-column pairs, stable order. MAC is upper
     * case without separators (the ecosystem convention for wheel MACs).
     */
    fun extraPairs(): List<String> {
        val out = ArrayList<String>(6)
        field("ble_name")?.let { out.add("wheel.name=$it") }
        field("ble_mac")?.let { out.add("wheel.mac=" + it.replace(":", "").replace("-", "").uppercase()) }
        field("brand")?.let { out.add("wheel.brand=$it") }
        field("model")?.let { out.add("wheel.model=$it") }
        field("serial")?.let { out.add("wheel.serial=$it") }
        field("firmware")?.let { out.add("wheel.firmware=$it") }
        return out
    }

    /** Same shape and blank-handling as [buildWheelMetaJson] - it delegates to it. */
    fun toJson(): String? = buildWheelMetaJson(
        brand = field("brand"),
        model = field("model"),
        serial = field("serial"),
        bleMac = field("ble_mac"),
        bleName = field("ble_name"),
        firmware = field("firmware"),
    )
}

/**
 * Builds a JSON object string with the connected wheel's metadata.
 * Returns null if ALL fields are null or blank (nothing to record).
 * Only non-null, non-blank values are included in the JSON.
 */
fun buildWheelMetaJson(
    brand: String?,
    model: String?,
    serial: String?,
    bleMac: String?,
    bleName: String?,
    firmware: String?,
): String? {
    val obj = JSONObject()
    if (!brand.isNullOrBlank())    obj.put("brand", brand)
    if (!model.isNullOrBlank())    obj.put("model", model)
    if (!serial.isNullOrBlank())   obj.put("serial", serial)
    if (!bleMac.isNullOrBlank())   obj.put("ble_mac", bleMac)
    if (!bleName.isNullOrBlank())  obj.put("ble_name", bleName)
    if (!firmware.isNullOrBlank()) obj.put("firmware", firmware)
    return if (obj.length() == 0) null else obj.toString()
}

/**
 * One recording's wheel: the [WheelIdentity] it accumulates and the CSV
 * Extra-column pairs it streams. Both phones' recorders run this, so a trip
 * recorded on either names its wheel the same way.
 *
 * One key=value pair leaves per written row, so a multi-key event (the
 * identity block) spills onto consecutive rows exactly like euc.world's extra
 * column does. The full block goes out on every (re)connect, single pairs as
 * late fields arrive, and a wheel.disconnected marker on a drop, so a trip
 * that switches wheels mid-ride stays reconstructable from the file alone.
 */
class TripWheelLog {
    val identity = WheelIdentity()

    // Extra-column event queue, one pair per written row.
    private val pending = ArrayDeque<String>()
    // Pairs already written for the CURRENT connection block, so a late
    // serial adds one row instead of repeating the whole block, while a
    // reconnect (cleared set) re-emits everything.
    private val emitted = HashSet<String>()
    // Last identity JSON already flushed to the trip row, so the mid-ride
    // persist writes only when it changes (about once per ride).
    private var lastPersisted: String? = null

    /** A new recording: never inherit the previous ride's wheel. */
    fun reset() {
        identity.clear()
        lastPersisted = null
        pending.clear()
        emitted.clear()
    }

    /** Fold what the wheel reports right now in; blanks are ignored. */
    fun capture(wheel: WheelRepository, bleMac: String?) {
        identity.merge(
            brand = wheel.connectedBrand.value,
            model = wheel.modelName.value,
            serial = wheel.wheelSerial.value,
            bleMac = bleMac,
            bleName = wheel.connectedDeviceName.value,
            firmware = wheel.firmwareVersion.value,
        )
    }

    /** Queue any not-yet-written identity pairs of the current connection block. */
    fun queueIdentity() {
        for (pair in identity.extraPairs()) {
            if (emitted.add(pair)) pending.addLast(pair)
        }
    }

    /** The wheel came back: the next queue re-emits the whole block. */
    fun onReconnect() = emitted.clear()

    /** The wheel dropped mid-recording. */
    fun onDisconnect() = pending.addLast(DISCONNECTED)

    /** The pair for the row about to be written, or null. */
    fun nextExtra(): String? = pending.removeFirstOrNull()

    /** The identity JSON when it changed since the last call that returned one. */
    fun jsonIfChanged(): String? {
        val json = identity.toJson() ?: return null
        if (json == lastPersisted) return null
        lastPersisted = json
        return json
    }

    companion object {
        const val DISCONNECTED = "wheel.disconnected=1"

        private val PERIPHERAL_ID = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

        /**
         * [address] as a wheel MAC, or null for a CoreBluetooth peripheral id
         * (iOS never shows the MAC, and the id is per phone, so writing it as
         * wheel.mac would name a different wheel on every iPhone).
         */
        fun macOrNull(address: String?): String? =
            address?.takeUnless { PERIPHERAL_ID.matches(it) }
    }
}
