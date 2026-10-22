package com.eried.eucplanet.wear.ui.components.preview

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.eried.eucplanet.hud.protocol.MapLayers
import com.eried.eucplanet.hud.protocol.WatchMapCue
import com.eried.eucplanet.hud.protocol.WatchMapFix
import com.eried.eucplanet.hud.protocol.WatchMapFrame
import com.eried.eucplanet.hud.protocol.WatchMapLocationStatus
import com.eried.eucplanet.hud.protocol.WatchMapPoint
import com.eried.eucplanet.hud.protocol.WatchMapRoute
import com.eried.eucplanet.hud.protocol.WatchMapSnapshot
import com.eried.eucplanet.wear.bridge.WatchState
import com.eried.eucplanet.wear.ui.utils.WatchUnits

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Large round",
    device = "id:wearos_large_round",
    widthDp = 227,
    heightDp = 227,
    locale = "en",
    fontScale = 1f,
    showSystemUi = false,
)
internal annotation class WatchLargeRoundPreview

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.BINARY)
@Preview(
    name = "Small round, 1.3",
    device = "id:wearos_small_round",
    widthDp = 192,
    heightDp = 192,
    locale = "en",
    fontScale = 1.3f,
    showSystemUi = false,
)
internal annotation class WatchSmallRoundPreview

internal data class WatchPreviewData(
    val state: WatchState,
    val mapSnapshot: WatchMapSnapshot,
    val lastPushAtMs: Long,
    val nowMs: Long,
    val watchBatteryPercent: Int,
)

internal object WatchPreviewFixtures {
    val live: WatchPreviewData by lazy {
        WatchPreviewData(
            state = WatchState(
                connected = true,
                phoneSynced = true,
                speedKmh = 36f,
                pwmPercent = 45f,
                voltage = 95.4f,
                speedUnit = "kmh",
                wheelName = "EUC",
                batteryPercent = 78,
                phoneBatteryPercent = 64,
                current = 12.3f,
                temperatureC = 42f,
                tripKm = 12.34f,
                torque = 5.6f,
                maxSpeedKmh = 70f,
            ),
            mapSnapshot = WatchMapSnapshot(
                frame = WatchMapFrame(
                    phoneSessionId = "phone-1",
                    viewerId = "viewer-1",
                    viewerEpoch = 1L,
                    presenceSequence = 1L,
                    sequence = 1L,
                    enabled = true,
                    headingUp = false,
                    layerId = MapLayers.OSM,
                    unavailableTiles = emptyList(),
                    locationStatus = WatchMapLocationStatus.LIVE,
                    fix = WatchMapFix(
                        point = WatchMapPoint(59.91, 10.75),
                        ageMs = 100L,
                    ),
                    anchor = WatchMapPoint(59.91, 10.75),
                    fixMaxAgeMs = 2_000L,
                    headingDeg = 10f,
                    navigationSessionId = "nav-1",
                    navigationActive = true,
                    routeRevision = 1L,
                    target = WatchMapPoint(59.92, 10.76),
                    cue = WatchMapCue(
                        angleDeg = 45f,
                        primary = "",
                        distance = "100 ${WatchUnits.distanceUnit("m")}",
                        arrived = false,
                    ),
                ),
                route = WatchMapRoute(
                    navigationSessionId = "nav-1",
                    revision = 1L,
                    coordinates = doubleArrayOf(59.91, 10.75, 59.92, 10.76),
                ),
                linkLive = true,
                fixLive = true,
            ),
            lastPushAtMs = 10_000L,
            nowMs = 10_000L,
            watchBatteryPercent = 82,
        )
    }

    val stale: WatchPreviewData by lazy {
        live.copy(
            nowMs = 13_000L,
            mapSnapshot = live.mapSnapshot.copy(linkLive = false, fixLive = false),
        )
    }

    val maximum: WatchPreviewData by lazy {
        live.copy(
            state = live.state.copy(
                pwmPercent = 100f,
                speedKmh = 120f,
                voltage = 180f,
            ),
        )
    }
}

internal class WatchPreviewParameterProvider : PreviewParameterProvider<WatchPreviewData> {
    override val values: Sequence<WatchPreviewData>
        get() = sequenceOf(
            WatchPreviewFixtures.live,
            WatchPreviewFixtures.stale,
            WatchPreviewFixtures.maximum,
        )
}
