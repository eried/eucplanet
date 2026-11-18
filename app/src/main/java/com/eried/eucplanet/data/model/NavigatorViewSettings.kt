package com.eried.eucplanet.data.model

/**
 * The map's navigator view: the 4 telemetry tiles beside the map while
 * navigating, and whether the camera keeps north up instead of turning with
 * the rider. Nested so AppSettings stays under the constructor-argument limit.
 */
data class NavigatorViewSettings(
    /** CSV of dashboard metric keys (MetricCatalog), one per tile. */
    val metrics: String = DEFAULT_METRICS,
    val northUp: Boolean = false,
) {
    companion object {
        const val SLOTS = 4
        /** LOAD is the catalog's PWM. */
        const val DEFAULT_METRICS = "VOLTAGE,BATTERY,TRIP,LOAD"
        val DEFAULT_KEYS: List<String> = DEFAULT_METRICS.split(',')
        /** A tile the rider emptied with the slot picker's None item. */
        const val NONE = "NONE"

        /**
         * Always [SLOTS] keys; [NONE] stays [NONE], a blank or unknown slot
         * takes that slot's default.
         */
        fun metricSlots(csv: String, known: Set<String>): List<String> {
            val parts = csv.split(',').map { it.trim() }
            return List(SLOTS) { i ->
                parts.getOrNull(i)?.takeIf { it == NONE || it in known } ?: DEFAULT_KEYS[i]
            }
        }

        /** [csv] with tile [index] set to [key] (or [NONE]); an out-of-range index changes nothing. */
        fun withSlot(csv: String, index: Int, key: String, known: Set<String>): String {
            val slots = metricSlots(csv, known).toMutableList()
            if (index in slots.indices) slots[index] = key
            return slots.joinToString(",")
        }
    }
}
