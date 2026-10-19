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

        /** Always [SLOTS] keys; a blank or unknown slot takes that slot's default. */
        fun metricSlots(csv: String, known: Set<String>): List<String> {
            val parts = csv.split(',').map { it.trim() }
            return List(SLOTS) { i ->
                parts.getOrNull(i)?.takeIf { it in known } ?: DEFAULT_KEYS[i]
            }
        }
    }
}
