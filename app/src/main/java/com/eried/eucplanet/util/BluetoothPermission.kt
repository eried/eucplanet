package com.eried.eucplanet.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether the app may open a Bluetooth connection right now.
 *
 * Android 12+ needs the Nearby devices permission (BLUETOOTH_CONNECT), and
 * without it connectGatt throws a SecurityException that closes the app.
 * Android can take that permission back by itself from an app that went
 * unused for a few months, so a paired radar, GPS box or ENGO that
 * auto-reconnects at start crashed the app on every launch. Below Android 12
 * there is no such permission and this is always true. It is the same check
 * WheelService and the dashboard use before connecting to the wheel; the
 * dashboard's "Bluetooth not allowed" warning tells the rider what to do.
 */
fun Context.canConnectBluetooth(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
        PackageManager.PERMISSION_GRANTED
