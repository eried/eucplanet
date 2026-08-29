package com.eried.eucplanet.ui.navigator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eried.eucplanet.R
import com.eried.eucplanet.share.ShareLink
import com.eried.eucplanet.ui.theme.appColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Join tab's camera area: a square live preview that reads a friend's
 * share QR, so a rider joining a ride can point the phone at their screen
 * instead of copying a 60 character URL.
 *
 * A CameraX preview plus an ImageAnalysis stream, decoded with ZXing. The
 * analysis keeps only the latest frame: decoding is slower than the camera,
 * and a queue of stale frames would only delay the hit the rider is waiting
 * for. The first frame whose text parses as a share link is handed up.
 *
 * It is an area inside the share dialog, not a dialog of its own: the tab IS
 * the scanner, so there is nothing to open and nothing to explain, and no
 * caption sits under a live camera saying what to point it at.
 */
@Composable
fun ShareQrScannerArea(
    onLink: (ShareLink) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // The callback is read from the camera's analyzer thread long after this
    // composition ran, so it is kept fresh rather than captured once.
    val latestOnLink by rememberUpdatedState(onLink)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Nothing is asked on open: the rider picked the Join tab, not a
    // permission prompt, and the button below is the ask. Set once the system
    // dialog has come back with a no, which is what separates "not asked yet"
    // from "answered no" when reading the rationale flag.
    var refused by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        refused = !ok
    }
    // Once the rider has turned the ask off for good, Android answers the
    // launcher instantly with the same no and shows nothing, so the button
    // has to lead somewhere the answer can still be changed.
    val activity = remember(context) { context.findActivity() }
    val permanentlyDenied = refused && activity != null &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)

    /** When the camera last read a QR that is not a share link. Held as a
     *  clock rather than a flag so the note clears itself: a code from another
     *  app stays in frame for as long as the rider holds it there, and a line
     *  that only cleared on the next decoded frame would sit under the preview
     *  until they moved the phone. */
    var invalidAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(invalidAtMs) {
        if (invalidAtMs == 0L) return@LaunchedEffect
        delay(INVALID_NOTE_MS)
        invalidAtMs = 0L
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .widthIn(max = shareBlockMaxWidth())
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.appColors.surfaceVariant)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center
        ) {
            if (granted) {
                QrCameraPreview(
                    lifecycleOwner = lifecycleOwner,
                    onLink = { link -> latestOnLink(link) },
                    onUnreadable = { invalidAtMs = System.currentTimeMillis() },
                )
            } else {
                CameraPermissionPrompt(
                    onAllow = {
                        if (permanentlyDenied) context.openAppSettings()
                        else permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }
        }
        if (invalidAtMs != 0L) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.share_link_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.appColors.statusDanger,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** How long a "that is not a share link" note stays under the preview. */
private const val INVALID_NOTE_MS = 2_500L

/** The camera square before the rider has said yes: the subject of the ask,
 *  and one button. No paragraph explaining a camera. */
@Composable
private fun CameraPermissionPrompt(onAllow: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp)
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = stringResource(R.string.share_camera_needed),
            tint = MaterialTheme.appColors.textSecondary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onAllow,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.appColors.outline),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.appColors.textButton
            )
        ) { Text(stringResource(R.string.share_camera_allow), maxLines = 1) }
    }
}

/** The Activity behind a composable's context, which can be a wrapper. Needed
 *  for the rationale flag, which only an Activity can be asked about. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** This app's page in system settings, where a permission that was turned off
 *  for good can be turned back on. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

/** The live camera surface and the decode loop behind it. */
@Composable
private fun QrCameraPreview(
    lifecycleOwner: LifecycleOwner,
    onLink: (ShareLink) -> Unit,
    /** A frame that decoded to something which is not a share link. Reported
     *  so the tab can say so once, quietly, and carry on scanning. */
    onUnreadable: () -> Unit,
) {
    // One decode at a time on one thread: MultiFormatReader keeps state
    // between calls and is not safe to share across threads.
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember { qrReader() }
    // A QR sits in frame for many frames, and the dialog takes a moment to
    // close: without this latch the same link would be handed back a dozen
    // times and the join would fire repeatedly.
    val handled = remember { AtomicBoolean(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var bindStarted by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            PreviewView(c).also {
                it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                it.scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { view ->
            // The update lambda runs on every recomposition; binding the
            // camera more than once floods CameraX with rebinds (the HUD's
            // camera screen learned this the hard way).
            if (bindStarted) return@AndroidView
            bindStarted = true
            val future = ProcessCameraProvider.getInstance(view.context)
            future.addListener({
                val cameraProvider = runCatching { future.get() }.getOrNull()
                    ?: return@addListener
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = view.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val text = runCatching { proxy.decodeQr(reader) }.getOrNull()
                    proxy.close()
                    val link = text?.let { parseShareText(it) }
                    val main = ContextCompat.getMainExecutor(view.context)
                    if (link != null) {
                        if (handled.compareAndSet(false, true)) main.execute { onLink(link) }
                    } else if (text != null) {
                        // A QR that is not ours: a wifi code, a product
                        // barcode. Scanning carries on, the tab notes it.
                        main.execute { onUnreadable() }
                    }
                }
                runCatching {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                }
            }, ContextCompat.getMainExecutor(view.context))
        }
    )
}

/**
 * The luminance (Y) plane of one camera frame, decoded as a QR code.
 *
 * The plane is copied row by row rather than handed to ZXing whole: CameraX
 * pads rows out to the hardware's stride, so the buffer is wider than the
 * image and a straight wrap would decode the padding as image data (and can
 * run off the end of a short final row).
 */
private fun ImageProxy.decodeQr(reader: MultiFormatReader): String? {
    val plane = planes.firstOrNull() ?: return null
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val gray = ByteArray(w * h)
    if (pixelStride == 1 && rowStride == w) {
        if (buffer.remaining() < gray.size) return null
        buffer.get(gray)
    } else {
        val row = ByteArray(rowStride)
        var out = 0
        for (y in 0 until h) {
            val take = minOf(rowStride, buffer.remaining())
            if (take < (w - 1) * pixelStride + 1) return null
            buffer.get(row, 0, take)
            for (x in 0 until w) gray[out++] = row[x * pixelStride]
        }
    }
    return decodeQrLuminance(gray, w, h, reader)
}

/**
 * Decode one tightly packed 8-bit greyscale frame. Split out from the camera
 * path so the ZXing wiring - the QR-only hint, the luminance source geometry
 * and the reader's between-frame reset - can be pinned by a JVM test instead
 * of only by pointing a phone at a code.
 *
 * [reader] must already carry the POSSIBLE_FORMATS hint: decodeWithState is
 * what keeps it, where plain decode() would clear the hints on every call.
 */
internal fun decodeQrLuminance(
    gray: ByteArray,
    width: Int,
    height: Int,
    reader: MultiFormatReader,
): String? {
    if (width <= 0 || height <= 0 || gray.size < width * height) return null
    val source = PlanarYUVLuminanceSource(gray, width, height, 0, 0, width, height, false)
    val text = runCatching {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
    // The reader caches the previous frame's decoding hints and state; without
    // the reset a frame that failed can poison the next one.
    reader.reset()
    return text
}

/** A reader hinted to QR alone, shared by the camera path and its test. */
internal fun qrReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}
