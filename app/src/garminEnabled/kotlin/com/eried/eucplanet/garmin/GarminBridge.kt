package com.eried.eucplanet.garmin

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.arrowAngleDeg
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.nav.NavigationEngine
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone-side companion to the Connect IQ watch app in `garmin-watch-app/`.
 *
 * Mirrors `wear/.../WearBridge.kt` line-for-line where it makes sense: same
 * 5 Hz publish cadence, same key vocabulary (see [GarminKeys]), same
 * watch-side concerns (auto-wake on phone open, farewell on session tear-down,
 * remote vibrate hint, snapshot fields). The transport is different: Connect
 * IQ messages route through the Garmin Connect Mobile app rather than a
 * direct Bluetooth channel, so init can fail when Connect Mobile isn't
 * installed; we degrade quietly to a no-op rather than crashing the host app.
 *
 * Inject-and-forget: `EucPlanetApp.onCreate()` calls [start] once. The bridge
 * lives for the whole process so any Garmin device that comes online mid-ride
 * starts receiving telemetry without further wiring.
 */
@Singleton
class GarminBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val cheatState: com.eried.eucplanet.cheats.CheatState,
    private val externalGpsRepository: com.eried.eucplanet.data.repository.ExternalGpsRepository,
    private val tripRepository: com.eried.eucplanet.data.repository.TripRepository,
    private val navigationEngine: NavigationEngine,
    private val flicManager: FlicManager
) {
    companion object {
        private const val TAG = "GarminBridge"
        private const val PUBLISH_INTERVAL_MS = 200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app = IQApp(GARMIN_APP_UUID)

    /**
     * WIRELESS in release and on real phones: pairs through Garmin Connect
     * Mobile, which is how actual rider phones reach actual watches over
     * Bluetooth.
     *
     * TETHERED only on Android emulator + debug build: routes through
     * `adb forward tcp:7381 tcp:7381` to the Connect IQ simulator running
     * on the host. Lets us validate the full bridge flow without needing
     * a Garmin developer account on Connect Mobile or a physical watch.
     *
     * If we used TETHERED on a real phone, the SDK lists a phantom
     * "Simulator" device as if it were paired (because TETHERED mode
     * assumes one exists), which then shows up in the rider's Settings
     * → Device list as "Live" even when nothing's actually connected.
     */
    private val connectType: ConnectIQ.IQConnectType =
        if (com.eried.eucplanet.BuildConfig.DEBUG && isAndroidEmulator())
            ConnectIQ.IQConnectType.TETHERED
        else
            ConnectIQ.IQConnectType.WIRELESS

    private fun isAndroidEmulator(): Boolean {
        // Detection mirrors the well-known emulator fingerprint heuristics
        // used by Firebase / Crashlytics. Cheap, no SDK calls, no permission
        // needed; runs once at GarminBridge construction.
        val p = android.os.Build.PRODUCT.orEmpty()
        val fp = android.os.Build.FINGERPRINT.orEmpty()
        val model = android.os.Build.MODEL.orEmpty()
        val hw = android.os.Build.HARDWARE.orEmpty()
        return p.startsWith("sdk_") || p.contains("emulator") ||
            fp.startsWith("generic") || fp.contains("vbox") ||
            model.contains("Emulator", ignoreCase = true) ||
            hw.contains("ranchu") || hw.contains("goldfish")
    }

    private val connectIQ: ConnectIQ = ConnectIQ.getInstance(context, connectType)

    /** Devices we've bound message + status listeners to. Avoids duplicate
     *  registration when a status callback re-fires for a device we already
     *  saw. Cleared when [stop] is called. */
    private val registeredDevices = ConcurrentHashMap<Long, IQDevice>()

    @Volatile private var sdkReady = false
    @Volatile private var started = false

    /**
     * Names of currently-paired Garmin devices that have the EUC Planet
     * Connect IQ app installed. Surfaces in the Settings UI's "Paired
     * devices" card alongside the Wear OS list, so the rider sees the full
     * picture of what watches are receiving the dial right now.
     */
    private val _pairedDevices = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val pairedDevices: kotlinx.coroutines.flow.StateFlow<List<String>> = _pairedDevices

    /**
     * Rolling 1-second window of successful CIQ sendMessage callbacks,
     * exposed as a frames-per-second number for the Settings UI. The CIQ
     * Mobile SDK throttles the actual delivery near 1 Hz regardless of how
     * often we call sendMessage, so this surfaces the effective rate
     * (i.e., what the rider's watch will actually update at).
     */
    private val deliveredCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val _deliveryRateHz = kotlinx.coroutines.flow.MutableStateFlow(0.0)
    val deliveryRateHz: kotlinx.coroutines.flow.StateFlow<Double> = _deliveryRateHz

    fun start() {
        if (started) return
        started = true
        Log.i(TAG, "Garmin bridge starting (publish=${PUBLISH_INTERVAL_MS} ms)")

        // Async init. autoUI=false: we never want the SDK to pop its own
        // dialogs (install Connect, switch app, etc.) at host-app launch; if
        // a Garmin device isn't reachable the bridge stays dormant.
        try {
            connectIQ.initialize(context, /* autoUi = */ false, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    sdkReady = true
                    Log.i(TAG, "Connect IQ SDK ready")
                    bindKnownDevices()
                    pingWatchToWake()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                    Log.w(TAG, "Connect IQ init failed: $status")
                    // SERVICE_UNAVAILABLE typically means Garmin Connect Mobile
                    // isn't installed. The user can install it later and we'll
                    // pick up devices on the next host-app launch.
                }

                override fun onSdkShutDown() {
                    sdkReady = false
                    Log.i(TAG, "Connect IQ SDK shut down")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Connect IQ init threw: ${t.message}")
            return
        }

        // Rolling 1-second delivery-rate poller. Reads + resets the
        // [deliveredCount] every second so the Settings UI's per-device
        // card can show actual frames/sec on the CIQ transport (which the
        // SDK rate-caps near 1 Hz regardless of how often we call
        // sendMessage). Divides by paired-device count so the number reads
        // as "Hz per device" instead of "total Hz across all paired
        // Garmins" — riders care about their own watch's update rate.
        scope.launch {
            while (true) {
                delay(1_000L)
                val count = deliveredCount.getAndSet(0)
                val devices = registeredDevices.size.coerceAtLeast(1)
                _deliveryRateHz.value = count.toDouble() / devices
            }
        }

        // Publishing loop runs independent of sdkReady so the moment a device
        // connects it gets fresh state (sendStateToAll is a no-op until then).
        scope.launch {
            while (true) {
                try {
                    val s = settingsRepository.get()
                    val effTilt = if (wheelRepository.safetySpeedActive.value)
                        s.safetyTiltbackKmh else s.tiltbackSpeedKmh
                    val gaugeMax = (((effTilt / 10f).toInt() + 1) * 10f).coerceAtLeast(30f)
                    publish(
                        data = wheelRepository.wheelData.value,
                        state = wheelRepository.connectionState.value,
                        name = wheelRepository.modelName.value,
                        maxSpeed = gaugeMax,
                        settings = s
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "publish loop error", e)
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
    }

    /** Stop the bridge and release CIQ resources. Currently only called by
     *  test code; the production bridge runs for the lifetime of the process. */
    fun stop() {
        if (!started) return
        started = false
        try {
            for (device in registeredDevices.values) {
                runCatching { connectIQ.unregisterForDeviceEvents(device) }
                runCatching { connectIQ.unregisterForApplicationEvents(device, app) }
            }
            registeredDevices.clear()
            connectIQ.shutdown(context)
        } catch (_: Exception) { /* best effort */ }
    }

    /** Walks `knownDevices`, binds status + message listeners. CIQ requires
     *  the device to be registered before either side can exchange events. */
    private fun bindKnownDevices() {
        val devices = try { connectIQ.knownDevices ?: emptyList() } catch (e: Exception) {
            Log.w(TAG, "knownDevices failed: ${e.message}")
            return
        }
        Log.i(TAG, "known Garmin devices: ${devices.size}")
        devices.forEach(::bindDevice)
    }

    private fun bindDevice(device: IQDevice) {
        if (registeredDevices.putIfAbsent(device.deviceIdentifier, device) != null) return
        try {
            connectIQ.registerForDeviceEvents(device) { _, status ->
                Log.d(TAG, "device ${device.friendlyName} status=$status")
                refreshPairedDevices()
            }
            connectIQ.registerForAppEvents(device, app) { _, _, message, _ ->
                handleIncoming(device, message)
            }
            refreshPairedDevices()
        } catch (e: InvalidStateException) {
            Log.w(TAG, "register failed on ${device.friendlyName}: SDK not ready")
        } catch (e: Exception) {
            Log.w(TAG, "register failed on ${device.friendlyName}", e)
        }
    }

    /** Recompute the public [pairedDevices] list. Cheap; called from device
     *  status callbacks and on initial bind so the Settings UI's card
     *  reflects connect/disconnect events without polling. */
    private fun refreshPairedDevices() {
        val names = registeredDevices.values.map { it.friendlyName ?: "Garmin device" }
        if (names != _pairedDevices.value) {
            _pairedDevices.value = names
        }
    }

    /** Routes one inbound message from a watch into the same code paths the
     *  Wear OS bridge uses. The watch sends a `Dictionary{"cmd": <intent>}`. */
    private fun handleIncoming(device: IQDevice, message: List<Any>) {
        // CIQ delivers the payload as a single-element list when the watch
        // calls `Communications.transmit(payload)`. Defensive: tolerate both
        // shapes (dict-wrapped, or a raw string for an older watch build).
        val raw = message.firstOrNull() ?: return
        val cmd = when (raw) {
            is Map<*, *> -> raw[GarminControl.PAYLOAD_KEY]?.toString()
            is String -> raw
            else -> null
        } ?: return

        Log.i(TAG, "control from Garmin ${device.friendlyName}: $cmd")
        when {
            cmd == GarminControl.HORN -> wheelRepository.sendHorn()
            cmd == GarminControl.LIGHT_ON || cmd == GarminControl.LIGHT_OFF ->
                wheelRepository.toggleLight()
            cmd.startsWith(GarminControl.ACTION_PREFIX) ->
                flicManager.dispatchActionByName(cmd.removePrefix(GarminControl.ACTION_PREFIX))
            cmd.startsWith(GarminControl.WATCH_INFO_PREFIX) -> {
                val info = cmd.removePrefix(GarminControl.WATCH_INFO_PREFIX)
                Log.i(TAG, "Garmin watch info: $info")
                com.eried.eucplanet.diagnostics.DiagnosticsLogger.info("garmin: $info")
            }
            else -> Log.w(TAG, "unknown Garmin control: $cmd")
        }
    }

    fun pingWatchToWake() {
        scope.launch {
            try {
                if (!sdkReady) return@launch
                val settings = settingsRepository.get()
                if (!settings.watchAutoStart) return@launch
                sendKindToAll(GarminKeys.KIND_WAKE)
            } catch (e: Exception) {
                Log.d(TAG, "Garmin wake skipped: ${e.message}")
            }
        }
    }

    fun sendCloseToWatchBlocking() {
        try {
            if (!sdkReady) return
            sendKindToAll(GarminKeys.KIND_QUIT)
        } catch (e: Exception) {
            Log.d(TAG, "Garmin close skipped: ${e.message}")
        }
    }

    fun publishFarewell() {
        if (!started || !sdkReady) return
        try {
            val s = runBlocking { settingsRepository.get() }
            publish(
                data = com.eried.eucplanet.data.model.WheelData(),
                state = ConnectionState.DISCONNECTED,
                name = wheelRepository.modelName.value,
                maxSpeed = 30f,
                settings = s
            )
        } catch (e: Exception) {
            Log.d(TAG, "farewell publish skipped: ${e.message}")
        }
    }

    private fun sendKindToAll(kind: String) {
        val payload = HashMap<String, Any>(1).apply { put(GarminKeys.KIND, kind) }
        sendToAll(payload)
    }

    private fun publish(
        data: com.eried.eucplanet.data.model.WheelData,
        state: ConnectionState,
        name: String?,
        maxSpeed: Float,
        settings: AppSettings
    ) {
        if (!sdkReady || registeredDevices.isEmpty()) return
        val payload = encodeSnapshot(data, state, name, maxSpeed, settings)
        sendToAll(payload)
    }

    private fun sendToAll(payload: Map<String, Any>) {
        for (device in registeredDevices.values) {
            try {
                connectIQ.sendMessage(device, app, payload) { _, _, status ->
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        deliveredCount.incrementAndGet()
                    } else {
                        Log.d(TAG, "send to ${device.friendlyName}: $status")
                    }
                }
            } catch (e: InvalidStateException) {
                sdkReady = false
                return
            } catch (e: ServiceUnavailableException) {
                Log.d(TAG, "send to ${device.friendlyName}: Connect Mobile gone")
            } catch (e: Exception) {
                Log.w(TAG, "send to ${device.friendlyName} failed", e)
            }
        }
    }

    private fun encodeSnapshot(
        data: com.eried.eucplanet.data.model.WheelData,
        state: ConnectionState,
        name: String?,
        maxSpeed: Float,
        settings: AppSettings
    ): Map<String, Any> {
        val nav = navigationEngine.navState.value
        val navShow = nav.active && !nav.minimized && nav.cueVisible && settings.watchShowNavigation
        val gps = computeGpsExtraSpeed(settings)
        val speedUnit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
        val distanceUnit = com.eried.eucplanet.util.Units.effectiveDistanceUnit(settings)
        val tempUnit = com.eried.eucplanet.util.Units.effectiveTempUnit(settings)

        return buildMap {
            put(GarminKeys.KIND, GarminKeys.KIND_STATE)
            put(GarminKeys.CONNECTED, state == ConnectionState.CONNECTED)
            put(GarminKeys.WHEEL_NAME, name ?: "")
            put(GarminKeys.SPEED, data.speed * cheatState.speedDisplayMultiplier.value)
            put(GarminKeys.BATTERY, data.batteryPercent)
            put(GarminKeys.PHONE_BATT, readPhoneBatteryPercent())
            put(GarminKeys.VOLTAGE, data.voltage)
            put(GarminKeys.CURRENT, data.current)
            put(GarminKeys.PWM, data.pwm)
            put(GarminKeys.TEMP, data.maxTemperature)
            put(GarminKeys.TRIP_KM, data.tripDistance)
            put(GarminKeys.TORQUE, data.torque)
            put(GarminKeys.LIGHT_ON, data.lightOn)
            put(GarminKeys.MAX_SPEED, maxSpeed)
            put(GarminKeys.HAS_HORN, true)
            put(GarminKeys.HAS_LIGHT, true)
            put(GarminKeys.UNIT_SPEED, speedUnit)
            put(GarminKeys.UNIT_DISTANCE, distanceUnit)
            put(GarminKeys.UNIT_TEMP, tempUnit)
            put(GarminKeys.IMPERIAL, speedUnit == "mph")
            put(GarminKeys.ACCENT, settings.accentColor)
            put(GarminKeys.OPT_KEEP_ON, settings.watchKeepScreenOn)
            put(GarminKeys.OPT_SHOW_WHEEL_BATT, settings.watchShowWheelBattery)
            put(GarminKeys.OPT_SHOW_PHONE_BATT, settings.watchShowPhoneBattery)
            put(GarminKeys.OPT_SHOW_WATCH_BATT, settings.watchShowWatchBattery)
            put(GarminKeys.OPT_PWM_DISPLAY, settings.watchPwmDisplay)
            put(GarminKeys.OPT_SHOW_SPEED_UNIT, settings.watchShowSpeedUnit)
            put(GarminKeys.OPT_PRIORITIZE_PWM, settings.watchPrioritizePwm)
            put(GarminKeys.OPT_DIAL_ROTATION, settings.watchDialRotationDeg)
            put(GarminKeys.OPT_GAUGE_BAND, settings.showGaugeColorBand)
            put(GarminKeys.OPT_GAUGE_ORANGE, settings.gaugeOrangeThresholdPct)
            put(GarminKeys.OPT_GAUGE_RED, settings.gaugeRedThresholdPct)
            put(GarminKeys.STEM1_CLICK, settings.watchStem1Click)
            put(GarminKeys.STEM1_HOLD, settings.watchStem1Hold)
            put(GarminKeys.STEM2_CLICK, settings.watchStem2Click)
            put(GarminKeys.STEM2_HOLD, settings.watchStem2Hold)
            put(GarminKeys.SCREEN1_CLICK, settings.watchScreen1Click)
            put(GarminKeys.SCREEN1_HOLD, settings.watchScreen1Hold)
            put(GarminKeys.SCREEN2_CLICK, settings.watchScreen2Click)
            put(GarminKeys.SCREEN2_HOLD, settings.watchScreen2Hold)
            put(GarminKeys.HAPTIC_ON_ACTION, settings.watchHapticOnAction)
            // CIQ Dictionary doesn't accept NaN; use a sentinel and a boolean flag.
            put(GarminKeys.GPS_SPEED, gps?.first ?: -1f)
            put(GarminKeys.GPS_SOURCE, gps?.second ?: "")
            put(GarminKeys.NAV_ACTIVE, navShow)
            put(GarminKeys.NAV_ANGLE, nav.arrowAngleDeg())
            put(GarminKeys.NAV_PRIMARY, nav.primaryText)
            put(GarminKeys.NAV_DISTANCE, nav.distanceText)
            put(GarminKeys.NAV_ARRIVED, nav.arrived)
            put(GarminKeys.TIMESTAMP, System.currentTimeMillis())
        }
    }

    /** Mirror of [com.eried.eucplanet.wear.WearBridge.computeGpsExtraSpeed].
     *  Keep in sync if either changes — the watch dial mirrors the phone
     *  dashboard's gpsExtraSpeed indicator for parity with the Wear OS dial. */
    private fun computeGpsExtraSpeed(settings: AppSettings): Pair<Float, String>? {
        if (!settings.gpsShowOnDashboard) return null
        val externalSample = externalGpsRepository.currentSample.value
        val location = tripRepository.currentLocation.value
        val externalFresh = externalSample != null &&
            System.currentTimeMillis() - externalSample.timestamp < 5_000L
        return when {
            settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            location != null && location.hasSpeed() ->
                (location.speed * 3.6f) to "PHONE"
            !settings.gpsPrioritizeExternal && externalFresh ->
                externalSample!!.speedKmh to "EXTERNAL"
            else -> null
        }
    }

    private fun readPhoneBatteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { lvl ->
            if (lvl in 0..100) return lvl
        }
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0
    }
}
