package com.eried.eucplanet.survey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.service.WheelService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug-only receiver that lets a host script (Python + adb shell am broadcast)
 * drive the UI survey harness in DELETE_LATER_SURVEY_UI/. Two actions:
 *
 *   - SURVEY_APPLY: overwrite the dashboard layout JSON blobs in AppSettings
 *     so the next screenshot shows a freshly randomised configuration.
 *   - SURVEY_CONNECT_VIRTUAL: start WheelService with VIRTUAL:<id> so there
 *     is live telemetry on the dashboard when the screenshots are captured.
 *
 * Only present in debug builds — never merged into the release manifest —
 * so the production app surface stays unchanged. See the matching debug
 * AndroidManifest at app/src/debug/AndroidManifest.xml.
 */
@AndroidEntryPoint
class SurveyControlReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SURVEY_APPLY -> applyConfig(intent)
            ACTION_SURVEY_CONNECT_VIRTUAL -> connectVirtual(context, intent)
        }
    }

    private fun applyConfig(intent: Intent) {
        // Optional fields — each one only overwrites if non-null. Lets a
        // caller change just the metric order, or just the action layout,
        // without resetting the rest. Values are base64-encoded by the
        // host script to dodge Windows shell quoting eating JSON braces
        // when am broadcast --es passes them through cmd.exe.
        fun decode(extra: String): String? = intent.getStringExtra(extra)?.let { encoded ->
            runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrElse { encoded }  // fallback: assume already decoded
        }
        val metricOrder = decode(EXTRA_METRIC_ORDER)
        val metricStats = decode(EXTRA_METRIC_STATS)
        val actionOrder = decode(EXTRA_ACTION_ORDER)
        val composites = decode(EXTRA_COMPOSITES)
        val customTiles = decode(EXTRA_CUSTOM_TILES)
        val actionGroups = decode(EXTRA_ACTION_GROUPS)
        Log.i(TAG, "SURVEY_APPLY metricOrder=${metricOrder?.length} stats=${metricStats?.length} " +
                "actionOrder=${actionOrder?.length} composites=${composites?.length} " +
                "customTiles=${customTiles?.length} actionGroups=${actionGroups?.length}")
        scope.launch {
            val current = settingsRepository.get()
            settingsRepository.update(
                current.copy(
                    dashboardMetricOrder = metricOrder ?: current.dashboardMetricOrder,
                    dashboardMetricStats = metricStats ?: current.dashboardMetricStats,
                    dashboardActionOrder = actionOrder ?: current.dashboardActionOrder,
                    dashboardCompositeMetrics = composites ?: current.dashboardCompositeMetrics,
                    dashboardCustomTiles = customTiles ?: current.dashboardCustomTiles,
                    dashboardActionGroups = actionGroups ?: current.dashboardActionGroups
                )
            )
        }
    }

    private fun connectVirtual(context: Context, intent: Intent) {
        val wheelId = intent.getStringExtra(EXTRA_WHEEL_ID) ?: "V14"
        val pseudoAddress = "VIRTUAL:$wheelId"
        Log.i(TAG, "SURVEY_CONNECT_VIRTUAL $pseudoAddress")
        val svcIntent = Intent(context, WheelService::class.java).apply {
            action = WheelService.ACTION_CONNECT
            putExtra(WheelService.EXTRA_ADDRESS, pseudoAddress)
            putExtra(WheelService.EXTRA_NAME, "Virtual $wheelId")
        }
        context.startForegroundService(svcIntent)
    }

    companion object {
        private const val TAG = "SurveyReceiver"

        const val ACTION_SURVEY_APPLY = "com.eried.eucplanet.SURVEY_APPLY"
        const val ACTION_SURVEY_CONNECT_VIRTUAL = "com.eried.eucplanet.SURVEY_CONNECT_VIRTUAL"

        const val EXTRA_METRIC_ORDER = "metric_order"
        const val EXTRA_METRIC_STATS = "metric_stats"
        const val EXTRA_ACTION_ORDER = "action_order"
        const val EXTRA_COMPOSITES = "composites"
        const val EXTRA_CUSTOM_TILES = "custom_tiles"
        const val EXTRA_ACTION_GROUPS = "action_groups"
        const val EXTRA_WHEEL_ID = "wheel_id"
    }
}
