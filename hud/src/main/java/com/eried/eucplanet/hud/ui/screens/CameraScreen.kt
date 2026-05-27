package com.eried.eucplanet.hud.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.eried.eucplanet.hud.R
import com.eried.eucplanet.hud.protocol.HudState
import com.eried.eucplanet.hud.ui.HudUnits
import com.eried.eucplanet.hud.ui.parseHexColor

/**
 * Rear-camera screen. Live preview from the HUD's rear camera with a small
 * speed readout pinned bottom-right.
 *
 * The HUD's rear camera is exposed through the standard camera2 HAL on every
 * E6-class device we've seen, so we use CameraX which picks the rear lens
 * automatically. If the device doesn't expose a camera (e.g. an emulator
 * during development), we show a static "unavailable" placeholder rather
 * than crash — the rider can switch screens with LEFT/RIGHT regardless.
 */
@Composable
fun CameraScreen(hud: HudState) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    val accent = parseHexColor(hud.accentArgb)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission && !cameraFailed) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PreviewView(c).also { view ->
                        view.scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { view ->
                    if (!cameraReady) {
                        val providerFuture = ProcessCameraProvider.getInstance(view.context)
                        providerFuture.addListener({
                            try {
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = view.surfaceProvider
                                }
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview
                                )
                                cameraReady = true
                            } catch (_: Throwable) {
                                cameraFailed = true
                            }
                        }, ContextCompat.getMainExecutor(view.context))
                    }
                }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = ctx.getString(R.string.hud_camera_unavailable),
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        // Speed overlay (bottom-right). Same unit code as the dashboard.
        val displaySpeed = HudUnits.speed(hud.speedKmh, hud.unitSpeed)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .background(Color(0xAA000000))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "%.0f %s".format(displaySpeed, HudUnits.speedSuffix(hud.unitSpeed)),
                color = accent,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    LaunchedEffect(hasPermission) {
        // No-op; the AndroidView update lambda is the bind point. This
        // LaunchedEffect just makes hasPermission a key the composer tracks
        // when the rider grants the permission mid-session (rare on HUDs
        // since they sideload, but harmless to handle.)
    }
}
