package com.eried.eucplanet.diagnostics

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import androidx.core.content.FileProvider
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.data.model.TripRecord
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.util.CrashHandler
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone

/** The Android side of the shared Wheel Diagnostics view model. */
class AndroidDiagnosticsPlatform(
    private val context: Context,
    private val tripRepository: TripRepository,
) : DiagnosticsPlatform {

    override fun sessionInfo(): List<String> = buildList {
        // App + build
        val branchSuffix = BuildConfig.GIT_BRANCH.takeIf { it.isNotBlank() && it != "main" }?.let { " branch=$it" } ?: ""
        add("app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build ${BuildConfig.BUILD_STAMP}$branchSuffix")
        add("package ${context.packageName}")

        // Phone hardware + Android OS
        add("phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        add("android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})  abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        add("locale ${Locale.getDefault()}  tz=${TimeZone.getDefault().id}")
        val dm: DisplayMetrics = context.resources.displayMetrics
        val nightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        add("display ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi (${if (nightMode) "dark" else "light"} theme)")

        // RAM
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val totalMb = mem.totalMem / (1024L * 1024L)
            val availMb = mem.availMem / (1024L * 1024L)
            add("memory: ${availMb} / ${totalMb} MB free (lowMem=${mem.lowMemory})")
        } catch (_: Exception) {}

        // Storage on the data partition
        try {
            val statFs = StatFs(context.filesDir.absolutePath)
            val totalGb = statFs.totalBytes / (1024.0 * 1024.0 * 1024.0)
            val freeGb = statFs.availableBytes / (1024.0 * 1024.0 * 1024.0)
            add("storage: %.1f / %.1f GB free".format(freeGb, totalGb))
        } catch (_: Exception) {}

        // Phone battery, useful when a connection drop coincides with low pwr
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            add("phone battery: ${pct}%${if (plugged) " (charging)" else ""}")
        } catch (_: Exception) {}

        // Network type, explains failed cloud sync / play-store update issues
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            add("network: $type")
        } catch (_: Exception) {}

        // BLE adapter state, names/MACs require runtime permission so we
        // only log what's safe without one
        try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = mgr?.adapter
            val on = adapter?.isEnabled == true
            add("ble adapter ${if (on) "ON" else "OFF/missing"}")
        } catch (_: Exception) {}
    }

    // Wear OS, list connected nodes so we know if a watch is paired
    override suspend fun lateSessionInfo(): List<String> = try {
        val nodes = withContext(Dispatchers.IO) {
            Tasks.await(Wearable.getNodeClient(context).connectedNodes)
        }
        if (nodes.isEmpty()) {
            listOf("wear: no paired nodes")
        } else {
            nodes.map { n -> "wear node: ${n.displayName} (${n.id}) nearby=${n.isNearby}" }
        }
    } catch (e: Exception) {
        listOf("wear: query failed (${e.javaClass.simpleName})")
    }

    override suspend fun trips(): List<TripRecord> = tripRepository.allTrips.first()

    override val listsInstalledApps = true

    override fun installedApps(): List<String> =
        context.packageManager.getInstalledApplications(0).map { it.packageName }

    override fun crashes(): List<Pair<String, Long>> =
        CrashHandler.listCrashes(context).map { it.name to it.length() }
}

/**
 * Shares the Service Mode log as a temp .txt under the app's cache dir.
 * The FileProvider entry for `cache-path name="diagnostics"` must exist
 * in res/xml/file_paths.xml or getUriForFile throws and we end up with
 * a silently dead Share button.
 */
fun shareDiagnosticsLog(context: Context, fileName: String, text: String) {
    val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
    val file = File(dir, fileName)
    val intent = try {
        file.writeText(text)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "EUC Planet diagnostics ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } catch (e: Exception) {
        // Don't swallow silently, surface in logcat so a future
        // misconfiguration is obvious instead of a dead button.
        android.util.Log.e("WheelDiagnostics", "Share failed", e)
        DiagnosticsLogger.note("share failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
    intent?.let { context.startActivity(Intent.createChooser(it, "Share log")) }
}
