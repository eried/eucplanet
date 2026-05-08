package com.eried.eucplanet.wear.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.eried.eucplanet.wear.R
import com.eried.eucplanet.wear.bridge.WatchState

/**
 * Vertical-pager second page on the dashboard. Shows the watch's *own* GPS
 * speed (not the wheel's BLE-reported speed) so the rider has an independent
 * reading even if the BLE link goes flaky. Uses [LocationManager] directly
 * to keep us off play-services-location and avoid bloating the wear APK.
 *
 * Permissions: requests `ACCESS_FINE_LOCATION` once at first paint. If the
 * user denies, the page shows a tap-to-retry hint. We never escalate to
 * background-location since this only runs while the screen is foregrounded.
 */
@Composable
fun GpsSpeedScreen(state: WatchState, accent: Color) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasFineLocation(context)) }
    var speedKmh by remember { mutableStateOf<Float?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    if (hasPermission) {
        DisposableEffect(Unit) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (location.hasSpeed()) speedKmh = location.speed * 3.6f
                }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            }
            try {
                lm?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 500L, 0f, listener, Looper.getMainLooper()
                )
            } catch (_: SecurityException) {
                // Permission revoked between the check and the request — ignore.
            }
            onDispose { lm?.removeUpdates(listener) }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(enabled = !hasPermission) {
                launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            },
        contentAlignment = Alignment.Center
    ) {
        val sw = maxWidth.value
        val iconDp = (sw * 0.13f).coerceIn(36f, 56f).dp
        val labelSp = (sw * 0.040f).coerceIn(11f, 14f).sp
        val numberSp = (sw * 0.245f).coerceIn(60f, 92f).sp
        val unitSp = (sw * 0.045f).coerceIn(13f, 18f).sp
        val helperSp = (sw * 0.038f).coerceIn(11f, 14f).sp

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SatelliteAlt,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(iconDp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.watch_gps_label),
                    fontSize = labelSp,
                    color = Color(0xFFB0B0B0)
                )
            }
            Spacer(Modifier.size(4.dp))
            when {
                !hasPermission -> Text(
                    text = stringResource(R.string.watch_gps_permission),
                    fontSize = helperSp,
                    color = accent,
                    fontWeight = FontWeight.Medium
                )
                speedKmh == null -> Text(
                    text = stringResource(R.string.watch_gps_waiting),
                    fontSize = helperSp,
                    color = Color(0xFFB0B0B0)
                )
                else -> Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.0f".format(WatchUnits.speed(speedKmh!!, state.imperialUnits)),
                        fontSize = numberSp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    if (state.showSpeedUnit) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = WatchUnits.speedUnit(state.imperialUnits),
                            fontSize = unitSp,
                            color = Color(0xFFB0B0B0),
                            modifier = Modifier.padding(bottom = (sw * 0.04f).coerceIn(10f, 16f).dp)
                        )
                    }
                }
            }
        }
    }
}

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
