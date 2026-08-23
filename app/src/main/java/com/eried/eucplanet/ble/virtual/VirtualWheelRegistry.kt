package com.eried.eucplanet.ble.virtual

/**
 * Registry of available virtual wheels: the two InMotion generations plus a
 * Begode; S18 and Lynx follow as their simulators land. The pseudo-address
 * "VIRTUAL:<id>" is what BleConnectionManager.connect() recognises to route
 * to the simulator instead of real GATT.
 */
object VirtualWheelRegistry {

    /** Builds a fresh wheel each time so internal state is reset on every connect. */
    private val factories: Map<String, () -> VirtualWheel> = mapOf(
        "V14" to ::V14VirtualWheel,
        "P6" to ::P6VirtualWheel,
        "V8S" to { InMotionV1VirtualWheel() },
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
