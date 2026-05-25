package com.eried.eucplanet.ble.virtual

/**
 * Registry of available virtual wheels. Currently V14 only; Phase 4-6 add
 * V12, V10F, S18, Lynx as their real adapters land. The pseudo-address
 * "VIRTUAL:<id>" is what BleConnectionManager.connect() recognises to route
 * to the simulator instead of real GATT.
 */
object VirtualWheelRegistry {

    /** Builds a fresh wheel each time so internal state is reset on every connect. */
    private val factories: Map<String, () -> VirtualWheel> = mapOf(
        "V14" to ::V14VirtualWheel,
        "P6" to ::P6VirtualWheel,
        "MASTER" to ::BegodeMasterVirtualWheel
    )

    fun all(): List<VirtualWheelInfo> =
        factories.keys.map { id ->
            // Build once just to extract the display name; cheap.
            val sample = factories.getValue(id)()
            VirtualWheelInfo(id = id, displayName = sample.displayName)
        }

    fun create(id: String): VirtualWheel? = factories[id]?.invoke()

    fun pseudoAddress(id: String): String = "$ADDRESS_PREFIX$id"

    fun parsePseudoAddress(address: String): String? =
        if (address.startsWith(ADDRESS_PREFIX)) address.removePrefix(ADDRESS_PREFIX) else null

    const val ADDRESS_PREFIX = "VIRTUAL:"
}

data class VirtualWheelInfo(val id: String, val displayName: String)
