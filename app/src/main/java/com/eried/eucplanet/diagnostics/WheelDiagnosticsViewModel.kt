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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.BuildConfig
import com.eried.eucplanet.ble.BleConnectionManager
import com.eried.eucplanet.ble.WheelAdapter
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import kotlinx.coroutines.flow.first
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

/**
 * Backs the Wheel Diagnostics dialog. Holds direct refs to the BLE manager
 * (so test commands can be fired) and the wheel adapter (so its diagnostic
 * command list comes through with no UI plumbing). Logger state is read
 * straight from the [DiagnosticsLogger] singleton.
 */
@HiltViewModel
class WheelDiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleConnectionManager,
    private val wheelAdapter: WheelAdapter,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository
) : ViewModel() {

    val entries: StateFlow<List<DiagnosticsLogger.Entry>> = DiagnosticsLogger.entries
    val enabled: StateFlow<Boolean> = DiagnosticsLogger.enabled

    /** Surface the wheel's reported model so the Commands tab can re-key its
     *  command list when the wheel finally identifies itself — otherwise a
     *  user who opens Service Mode before connecting sees an empty grid. */
    val modelName: StateFlow<String?> = wheelRepository.modelName

    /** Drop NOTEs with phone, app, BLE, and watch identity context so the
     *  shared log carries everything we'd ask for in a bug report. */
    fun captureSessionInfo() {
        // App + build
        DiagnosticsLogger.info("app v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build ${BuildConfig.BUILD_STAMP}")
        DiagnosticsLogger.info("package ${context.packageName}")

        // Phone hardware + Android OS
        DiagnosticsLogger.info("phone ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        DiagnosticsLogger.info("android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})  abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        DiagnosticsLogger.info("locale ${Locale.getDefault()}  tz=${TimeZone.getDefault().id}")
        val dm: DisplayMetrics = context.resources.displayMetrics
        val nightMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        DiagnosticsLogger.info("display ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi}dpi (${if (nightMode) "dark" else "light"} theme)")

        // RAM
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val totalMb = mem.totalMem / (1024L * 1024L)
            val availMb = mem.availMem / (1024L * 1024L)
            DiagnosticsLogger.info("memory: ${availMb} / ${totalMb} MB free (lowMem=${mem.lowMemory})")
        } catch (_: Exception) {}

        // Storage on the data partition
        try {
            val statFs = StatFs(context.filesDir.absolutePath)
            val totalGb = statFs.totalBytes / (1024.0 * 1024.0 * 1024.0)
            val freeGb = statFs.availableBytes / (1024.0 * 1024.0 * 1024.0)
            DiagnosticsLogger.info("storage: %.1f / %.1f GB free".format(freeGb, totalGb))
        } catch (_: Exception) {}

        // Phone battery — useful when a connection drop coincides with low pwr
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            DiagnosticsLogger.info("phone battery: ${pct}%${if (plugged) " (charging)" else ""}")
        } catch (_: Exception) {}

        // Network type — explains failed cloud sync / play-store update issues
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
            DiagnosticsLogger.info("network: $type")
        } catch (_: Exception) {}

        // BLE adapter state — names/MACs require runtime permission so we
        // only log what's safe without one
        try {
            val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = mgr?.adapter
            val on = adapter?.isEnabled == true
            DiagnosticsLogger.info("ble adapter ${if (on) "ON" else "OFF/missing"}")
        } catch (_: Exception) {}

        // Connection + wheel
        DiagnosticsLogger.info("connection state: ${bleManager.connectionState.value}")
        DiagnosticsLogger.info("wheel adapter: ${wheelAdapter.familyId}")
        wheelRepository.modelName.value?.let { DiagnosticsLogger.info("model: $it") }
        wheelRepository.firmwareVersion.value?.let { DiagnosticsLogger.info("firmware: $it") }
        val data = wheelRepository.wheelData.value
        DiagnosticsLogger.info("battery=${data.batteryPercent}%  voltage=${data.voltage}V  speed=${data.speed} km/h  lightOn=${data.lightOn}")

        // Wear OS — list connected nodes so we know if a watch is paired
        viewModelScope.launch {
            try {
                val nodes = withContext(Dispatchers.IO) {
                    Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                }
                if (nodes.isEmpty()) {
                    DiagnosticsLogger.info("wear: no paired nodes")
                } else {
                    nodes.forEach { n ->
                        DiagnosticsLogger.info("wear node: ${n.displayName} (${n.id}) nearby=${n.isNearby}")
                    }
                }
            } catch (e: Exception) {
                DiagnosticsLogger.info("wear: query failed (${e.javaClass.simpleName})")
            }
        }
    }

    fun diagnosticCommands(): List<DiagnosticCommand> = wheelAdapter.getDiagnosticCommands()

    /**
     * Service Mode is research-grade: a user with a V14 in front of them
     * still benefits from being able to browse the KingSong or Veteran
     * command catalogue. We expose every family's catalogue keyed by its
     * display name so the picker UI can show them all, regardless of what's
     * actually connected.
     */
    data class WheelFamily(
        val displayName: String,
        val commands: List<DiagnosticCommand>,
        val inspectPrefixes: List<String>
    )

    fun allWheelFamilies(): List<WheelFamily> {
        val composite = wheelAdapter as? com.eried.eucplanet.ble.CompositeWheelAdapter
            ?: return listOf(WheelFamily(
                displayName = wheelAdapter.familyDisplayName,
                commands = wheelAdapter.getDiagnosticCommands(),
                inspectPrefixes = wheelAdapter.inspectMessageTypes()
            ))
        return composite.allFamilies.map {
            WheelFamily(
                displayName = it.familyDisplayName,
                commands = it.getDiagnosticCommands(),
                inspectPrefixes = it.inspectMessageTypes()
            )
        }
    }

    fun fireCommand(cmd: DiagnosticCommand) {
        DiagnosticsLogger.cmd(cmd.label, cmd.bytes)
        bleManager.writeCommand(cmd.bytes)
    }

    enum class WrapMode { LITERAL, WRAP_EXTENDED, WRAP_V14_SHORT }

    /** Result of attempting to wrap user-typed hex into bytes. The dialog
     *  shows [bytes] in the read-only "Bytes to send" box on success and
     *  [error] there on failure, so the user always sees why SEND is or
     *  isn't enabled. */
    data class WrapResult(val bytes: ByteArray?, val error: String?) {
        val ok: Boolean get() = bytes != null
        val display: String
            get() = bytes?.joinToString(" ") { "%02x".format(it) } ?: (error ?: "")
    }

    fun fireRawHex(hexInput: String, mode: WrapMode = WrapMode.LITERAL): Boolean {
        val res = wrap(hexInput, mode)
        val bytes = res.bytes ?: return false
        DiagnosticsLogger.cmd("RAW(${mode.name})", bytes)
        bleManager.writeCommand(bytes)
        return true
    }

    /** Render bytes that would actually go on the wire, with reason on error. */
    fun previewHex(hexInput: String, mode: WrapMode): WrapResult = wrap(hexInput, mode)

    /** Re-emit the user input with consistent spacing every two hex chars. */
    fun formatHex(hexInput: String): String {
        val bytes = parseHex(hexInput) ?: return hexInput
        return bytes.joinToString(" ") { "%02x".format(it) }
    }

    private fun wrap(input: String, mode: WrapMode): WrapResult {
        if (input.isBlank()) return WrapResult(null, null)
        val cleaned = input.replace("0x", "", ignoreCase = true)
            .replace(",", " ")
            .replace("-", " ")
            .trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val joined = if (tokens.size == 1) tokens[0] else tokens.joinToString("")
        if (joined.length % 2 != 0) {
            return WrapResult(null, "Incomplete hex (odd character count)")
        }
        val invalid = joined.firstOrNull { c ->
            !((c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F'))
        }
        if (invalid != null) {
            return WrapResult(null, "Non-hex character: '$invalid'")
        }
        val bytes = try {
            ByteArray(joined.length / 2) { i ->
                joined.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            return WrapResult(null, "Could not parse hex")
        }
        if (bytes.size > MAX_RAW_BYTES) {
            return WrapResult(null, "Too long (max $MAX_RAW_BYTES bytes)")
        }
        val wrapped = when (mode) {
            WrapMode.LITERAL -> bytes
            WrapMode.WRAP_EXTENDED -> {
                if (bytes.isEmpty()) return WrapResult(null, "Need at least 1 byte")
                com.eried.eucplanet.ble.InMotionV2Protocol.buildExtendedPacket(
                    bytes[0],
                    if (bytes.size > 1) bytes.copyOfRange(1, bytes.size) else byteArrayOf()
                )
            }
            WrapMode.WRAP_V14_SHORT -> {
                if (bytes.isEmpty()) return WrapResult(null, "Need at least 1 byte")
                com.eried.eucplanet.ble.InMotionV2Protocol.buildPacket(
                    com.eried.eucplanet.ble.InMotionV2Protocol.Flags.DEFAULT,
                    bytes[0],
                    if (bytes.size > 1) bytes.copyOfRange(1, bytes.size) else byteArrayOf()
                )
            }
        }
        return WrapResult(wrapped, null)
    }

    companion object {
        const val MAX_RAW_BYTES = 64
    }

    fun addComment(text: String) {
        if (text.isBlank()) return
        DiagnosticsLogger.comment(text.trim())
    }

    /**
     * Drop a structured COMMENT into the log noting that the user picked a
     * specific byte from the live Inspect view. Used for protocol research:
     * tap the byte that matches the wheel UI value, the comment lands in
     * the log alongside the source frame so the offset can be tied to a
     * labelled value at export time.
     */
    fun logByteInspection(messageType: String, offset: Int, value: Int) {
        val hex = "%02x".format(value)
        DiagnosticsLogger.comment("inspect: $messageType[$offset] = 0x$hex ($value)")
    }

    /** Message-type prefixes the Inspect tab can subscribe to. The strings
     *  must match the prefix the adapter writes via DiagnosticsLogger.note. */
    /**
     * Flat list of inspect prefixes across all wheel families. Kept for
     * back-compat with the existing single-list dropdown until the UI
     * fully migrates to the per-family picker via [allWheelFamilies].
     */
    val inspectMessageTypes: List<String>
        get() = allWheelFamilies().flatMap { it.inspectPrefixes }

    /**
     * Categories the user can opt into when attaching extra context to the
     * Service Mode log. Each entry is rendered as one or more NOTE lines so
     * the rest of the log keeps its byte-stream feel.
     */
    enum class AttachCategory(val label: String) {
        APP_CONFIG("App config"),
        WHEEL_STATE("Wheel state snapshot"),
        TRIPS_SUMMARY("Trip history summary"),
        INSTALLED_APPS("Installed apps"),
        CRASHES("Recent crashes")
    }

    /**
     * Dump the selected categories into the live log as NOTE entries. Each
     * call is async so a slow PackageManager iteration doesn't block the UI.
     * Nothing leaves the device — these land in the same in-memory buffer
     * as everything else in Service Mode and only travel when the user
     * shares the log themselves.
     */
    fun attach(categories: Set<AttachCategory>) {
        if (categories.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (cat in categories) {
                runCatching {
                    when (cat) {
                        AttachCategory.APP_CONFIG -> attachAppConfig()
                        AttachCategory.WHEEL_STATE -> attachWheelState()
                        AttachCategory.TRIPS_SUMMARY -> attachTripsSummary()
                        AttachCategory.INSTALLED_APPS -> attachInstalledApps()
                        AttachCategory.CRASHES -> attachCrashes()
                    }
                }.onFailure {
                    DiagnosticsLogger.note("attach ${cat.name} failed: ${it.message}")
                }
            }
        }
    }

    private suspend fun attachAppConfig() {
        val s = settingsRepository.get()
        DiagnosticsLogger.note("--- BEGIN app config ---")
        // toString on the data class gives every field=value in one line; we
        // hard-wrap to roughly 200 chars so the log entries stay readable
        // and don't blow the per-line ellipsis the on-screen view uses.
        s.toString().chunked(200).forEach { DiagnosticsLogger.note(it) }
        DiagnosticsLogger.note("--- END app config ---")
    }

    private suspend fun attachWheelState() {
        val data = wheelRepository.wheelData.value
        val name = wheelRepository.modelName.value ?: "?"
        val fw = wheelRepository.firmwareVersion.value ?: "?"
        val state = wheelRepository.connectionState.value
        DiagnosticsLogger.note("--- BEGIN wheel state ---")
        DiagnosticsLogger.note("model=$name fw=$fw connection=$state")
        DiagnosticsLogger.note(data.toString())
        DiagnosticsLogger.note("--- END wheel state ---")
    }

    private suspend fun attachTripsSummary() {
        val all = tripRepository.allTrips.first()
        val totalKm = all.sumOf { it.distanceKm.toDouble() }
        DiagnosticsLogger.note("--- BEGIN trips summary ---")
        DiagnosticsLogger.note("trips=${all.size}  total=${"%.2f".format(totalKm)} km")
        // Only the most recent 20 — older trips are rarely interesting for
        // a fresh bug report and would crowd the log.
        all.sortedByDescending { it.startTime }.take(20).forEach { t ->
            val started = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(t.startTime))
            val durMin = ((t.endTime ?: t.startTime) - t.startTime) / 60_000
            DiagnosticsLogger.note("trip ${t.id}: $started  ${"%.2f".format(t.distanceKm)} km  ${durMin} min  upl=${t.uploadStatus}")
        }
        DiagnosticsLogger.note("--- END trips summary ---")
    }

    private suspend fun attachInstalledApps() {
        val pm = context.packageManager
        val apps = try {
            pm.getInstalledApplications(0)
                .map { it.packageName }
                .sorted()
        } catch (e: Exception) {
            DiagnosticsLogger.note("attach installed apps failed: ${e.message}")
            return
        }
        DiagnosticsLogger.note("--- BEGIN installed apps (${apps.size}) ---")
        // Pack 6 packages per line to keep the log scannable. Long line wrap
        // is fine; the dialog renders single-line and exports preserve full text.
        apps.chunked(6).forEach { DiagnosticsLogger.note(it.joinToString("  ")) }
        DiagnosticsLogger.note("--- END installed apps ---")
    }

    private fun attachCrashes() {
        val crashes = com.eried.eucplanet.util.CrashHandler.listCrashes(context)
        DiagnosticsLogger.note("--- BEGIN recent crashes (${crashes.size}) ---")
        if (crashes.isEmpty()) {
            DiagnosticsLogger.note("(none)")
        } else {
            crashes.take(5).forEach { f ->
                DiagnosticsLogger.note("crash ${f.name}  ${f.length()} bytes")
            }
        }
        DiagnosticsLogger.note("--- END recent crashes ---")
    }

    fun stopDiagnostics() {
        DiagnosticsLogger.disable()
        DiagnosticsLogger.clear()
    }

    /** Build a share intent backed by a temp .txt under the app's cache dir.
     *  The FileProvider entry for `cache-path name="diagnostics"` must exist
     *  in res/xml/file_paths.xml or getUriForFile throws and we end up with
     *  a silently dead Share button. */
    fun buildShareIntent(): Intent? {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "diagnostics-$stamp.txt")
        return try {
            file.writeText(DiagnosticsLogger.render())
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
            // Don't swallow silently — surface in logcat so a future
            // misconfiguration is obvious instead of a dead button.
            android.util.Log.e("WheelDiagnostics", "Share failed", e)
            DiagnosticsLogger.note("share failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseHex(input: String): ByteArray? {
        val cleaned = input.replace("0x", "", ignoreCase = true)
            .replace(",", " ")
            .replace("-", " ")
            .trim()
        if (cleaned.isBlank()) return null
        // Tolerate "AA BB CC" or "AABBCC" or mixed.
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val joined = if (tokens.size == 1) tokens[0] else tokens.joinToString("")
        if (joined.length % 2 != 0) return null
        return try {
            ByteArray(joined.length / 2) { i ->
                joined.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}
