package com.eried.eucplanet.data.model

/**
 * What the Android Auto screen shows, configured like the home screen widget:
 * the same [WidgetMetricType] and [WidgetActionType] registries, its own saved
 * choices. The car and the home screen are different jobs, the same reasoning
 * that split [WidgetSettings.standaloneActions] from the big widget's row.
 *
 * Nested to keep AppSettings' copy() under the JVM's 255 parameter slots.
 */
data class AndroidAutoSettings(
    /** CSV of [WidgetMetricType] keys, one per stat box beside the map. */
    val metrics: String = DEFAULT_METRICS,
    /**
     * CSV of [WidgetActionType] keys for the car's action strip. Navigate is
     * not here: it is always the first button, a navigation app without it
     * makes no sense, and Android Auto allows four buttons in all.
     */
    val actions: String = DEFAULT_ACTIONS,
    /**
     * Show a firing alarm on the car screen as a notification.
     *
     * The car screen itself is only visible when the rider has it in front of
     * them, and while navigating they do not. A notification is the one thing
     * the host will draw over Maps, which makes it the only way a PWM or
     * battery warning reaches a rider mid-route.
     */
    val alarmNotifications: Boolean = true,
) {
    companion object {
        const val METRIC_SLOTS = 4
        const val ACTION_SLOTS = 3
        const val DEFAULT_METRICS = "BATTERY,PWM,TEMP,TRIP"
        const val DEFAULT_ACTIONS = "HORN,LIGHT,RECORD"

        fun metricSlots(csv: String): List<String> = slots(csv, METRIC_SLOTS, WidgetMetricType.NONE)
        fun actionSlots(csv: String): List<String> = slots(csv, ACTION_SLOTS, WidgetActionType.NONE)

        private fun slots(csv: String, n: Int, none: String): List<String> {
            val parts = csv.split(",").map { it.trim() }
            return (0 until n).map { i -> parts.getOrNull(i)?.ifEmpty { none } ?: none }
        }
    }
}
