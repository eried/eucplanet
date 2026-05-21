package com.eried.eucplanet.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.MainActivity
import com.eried.eucplanet.R
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.WheelData
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WheelService : LifecycleService() {

    companion object {
        private const val TAG = "WheelService"
        const val CHANNEL_ID = "wheel_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.eried.eucplanet.CONNECT"
        const val ACTION_DISCONNECT = "com.eried.eucplanet.DISCONNECT"
        const val ACTION_START_RECORDING = "com.eried.eucplanet.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.eried.eucplanet.STOP_RECORDING"
        const val ACTION_START_NAVIGATION = "com.eried.eucplanet.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.eried.eucplanet.STOP_NAVIGATION"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_NAME = "device_name"
    }

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    @Volatile
    private var imperialCached: Boolean = false
    @Inject lateinit var voiceService: VoiceService
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var automationManager: AutomationManager
    @Inject lateinit var engineSoundEngine: EngineSoundEngine
    @Inject lateinit var wearBridge: com.eried.eucplanet.wear.WearBridge
    @Inject lateinit var navigationEngine: com.eried.eucplanet.nav.NavigationEngine

    // Voice announcement
    private var voiceJob: Job? = null
    private var lastConnectionState: ConnectionState? = null
    private var hadGpsFix = false
    private var lastLightOn: Boolean? = null

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val canUseLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val canUseBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

        if (!canUseLocation && !canUseBluetooth) {
            Log.e(TAG, "No permission for either location or bluetooth FGS type — stopping")
            stopSelf()
            return
        }

        var fgType = 0
        if (canUseBluetooth) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (canUseLocation) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

        try {
            startForeground(NOTIFICATION_ID, buildNotification(null), fgType)
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground denied, stopping", e)
            stopSelf()
            return
        }

        voiceService.initialize()

        // Update notification + check alarms + automations on telemetry updates
        lifecycleScope.launch {
            wheelRepository.wheelData.collect { data ->
                updateNotification(data)
                val settings = settingsRepository.get()
                automationManager.evaluate(settings)
                checkLightTransition(data.lightOn, settings)
                evaluateAutoRecordOnTelemetry(data, settings)
                if (settings.engineSoundEnabled) {
                    engineSoundEngine.pushTelemetry(data.speed, data.pwm)
                }
            }
        }

        // Apply engine settings + lifecycle on settings changes and connection
        lifecycleScope.launch {
            settingsRepository.settings.collect { s ->
                // Notification builder reads imperial without suspending —
                // mirror the latest value here every settings update.
                imperialCached = s.imperialUnits
                engineSoundEngine.applySettings(s)
                engineSoundEngine.setConnected(
                    wheelRepository.connectionState.value == ConnectionState.CONNECTED,
                    s
                )
            }
        }

        // Engine ducks itself while TTS is speaking.
        lifecycleScope.launch {
            voiceService.isSpeaking.collect { speaking ->
                engineSoundEngine.setVoiceActive(speaking)
            }
        }

        // Speed→RPM mapping uses the wheel's top speed as the reference so 30 km/h on a
        // V11 (max 50) revs harder than 30 km/h on a P6 (max 150).
        lifecycleScope.launch {
            wheelRepository.maxSpeedCap.collect { cap ->
                engineSoundEngine.setMaxSpeedRef(cap)
            }
        }

        // Start periodic voice announcements
        startVoiceLoop()

        // Auto-record idle timeout loop (1 Hz)
        startAutoRecordIdleLoop()

        // Reset idle timer whenever a new recording starts so the stop-after-idle
        // threshold is measured from the fresh start, not from some stale
        // lastMotionAtMs left over from a prior session.
        lifecycleScope.launch {
            tripRepository.recording.collect { isRecording ->
                if (isRecording) lastMotionAtMs = System.currentTimeMillis()
            }
        }

        // Monitor connection state for announcements + auto-record
        lifecycleScope.launch {
            wheelRepository.connectionState.collect { state ->
                if (lastConnectionState != null && state != lastConnectionState) {
                    val settings = settingsRepository.get()

                    when (state) {
                        ConnectionState.CONNECTED -> {
                            // Fresh connection — clear any session suspension of auto-lights
                            automationManager.clearLightsSuspension()
                            if (settings.announceConnection) {
                                voiceService.announceEvent(getString(R.string.voice_wheel_connected))
                            }
                            // Auto-record: start recording when wheel connects, unless the user
                            // gated it on "start in motion" — then the telemetry handler starts it.
                            if (settings.autoRecord && !settings.autoRecordStartInMotion &&
                                !tripRepository.recording.value) {
                                lifecycleScope.launch { tripRepository.startRecording() }
                            }
                            engineSoundEngine.setConnected(true, settings)
                        }
                        ConnectionState.DISCONNECTED -> {
                            // Only announce if we were actually connected (not just reconnect cycling)
                            if (lastConnectionState == ConnectionState.CONNECTED && settings.announceConnection) {
                                voiceService.announceEvent(getString(R.string.voice_wheel_disconnected))
                            }
                            lastLightOn = null
                            engineSoundEngine.setConnected(false, settings)
                        }
                        else -> {}
                    }
                }
                lastConnectionState = state
            }
        }

        // Monitor GPS signal for announcements
        lifecycleScope.launch {
            tripRepository.currentLocation.collect { location ->
                val settings = settingsRepository.get()
                if (settings.announceGps) {
                    if (location != null && !hadGpsFix) {
                        hadGpsFix = true
                        voiceService.announceEvent(getString(R.string.voice_gps_acquired))
                    } else if (location == null && hadGpsFix) {
                        hadGpsFix = false
                        voiceService.announceEvent(getString(R.string.voice_gps_lost))
                    }
                }
            }
        }

        // Start GPS tracking for trip recording (only if permission granted)
        if (canUseLocation) {
            tripRepository.startLocationUpdates()
        } else {
            Log.w(TAG, "Location permission not granted — GPS tracking disabled")
        }

        // Surface the next maneuver in the ongoing notification while navigating.
        // Throttled to 1 Hz so a per-GPS-fix NavState stream doesn't spam
        // notify(), but an active<->inactive flip always refreshes immediately
        // so the text reverts the moment navigation starts or ends.
        lifecycleScope.launch {
            var lastNavActive = false
            var lastNavNotifyMs = 0L
            navigationEngine.navState.collect { nav ->
                val now = System.currentTimeMillis()
                if (nav.active != lastNavActive || now - lastNavNotifyMs >= 1000L) {
                    lastNavActive = nav.active
                    lastNavNotifyMs = now
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(
                        NOTIFICATION_ID, buildNotification(wheelRepository.wheelData.value)
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME)
                if (address != null) {
                    wheelRepository.connect(address, name)
                }
            }
            ACTION_DISCONNECT -> {
                wheelRepository.disconnect()
            }
            ACTION_START_RECORDING -> {
                lifecycleScope.launch { tripRepository.startRecording() }
            }
            ACTION_STOP_RECORDING -> {
                lifecycleScope.launch { tripRepository.stopRecording() }
            }
            ACTION_START_NAVIGATION -> {
                // The engine drives navigation; the service just guarantees GPS
                // keeps flowing and the process stays alive while guiding.
                tripRepository.startLocationUpdates()
            }
            ACTION_STOP_NAVIGATION -> {
                navigationEngine.stop()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        // Send one last DataMap so the watch flips to its disconnected
        // ("—") state instantly. If the process is hard-killed and this
        // line never runs, the watch's 3-s stale timer kicks in as
        // fallback. Either way the rider never sees a frozen-stale dial.
        try { wearBridge.publishFarewell() } catch (_: Exception) {}
        voiceJob?.cancel()
        engineSoundEngine.stop()
        voiceService.shutdown()
        tripRepository.stopLocationUpdates()
        lifecycleScope.launch { tripRepository.stopRecording() }
        wheelRepository.disconnect()
        super.onDestroy()
    }

    // --- Auto-record motion gating ---

    // Timestamp of the last sample with motion (speed > 0) while connected.
    // Used by the idle-timeout loop to decide when to auto-stop.
    private var lastMotionAtMs: Long = 0L

    private fun evaluateAutoRecordOnTelemetry(
        data: WheelData,
        settings: com.eried.eucplanet.data.model.AppSettings
    ) {
        if (!settings.autoRecord) return
        val moving = kotlin.math.abs(data.speed) > 0f
        if (moving) lastMotionAtMs = System.currentTimeMillis()

        // Motion-linked loop: start on first motion and restart after each idle auto-stop.
        if (settings.autoRecordStartInMotion &&
            moving &&
            wheelRepository.connectionState.value == ConnectionState.CONNECTED &&
            !tripRepository.recording.value
        ) {
            lifecycleScope.launch { tripRepository.startRecording() }
        }
    }

    private fun startAutoRecordIdleLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(1000L)
                val settings = settingsRepository.get()
                if (!settings.autoRecord || !settings.autoRecordStartInMotion) continue
                if (!tripRepository.recording.value) continue

                val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
                val moving = connected && kotlin.math.abs(wheelRepository.wheelData.value.speed) > 0f
                if (moving) {
                    lastMotionAtMs = System.currentTimeMillis()
                    continue
                }

                // Idle = not moving OR disconnected. Give a short grace period when we just started
                // recording so we don't instantly stop a recording that began before the wheel moves.
                if (lastMotionAtMs == 0L) lastMotionAtMs = System.currentTimeMillis()
                val idleMs = System.currentTimeMillis() - lastMotionAtMs
                val thresholdMs = settings.autoRecordStopIdleSeconds * 1000L
                if (idleMs >= thresholdMs) {
                    Log.i(TAG, "Auto-stop: idle for ${idleMs / 1000}s (connected=$connected)")
                    tripRepository.stopRecording()
                }
            }
        }
    }

    private fun checkLightTransition(current: Boolean, settings: com.eried.eucplanet.data.model.AppSettings) {
        val previous = lastLightOn
        lastLightOn = current
        // Skip the first observation — that's the state we inherit on connect,
        // not a change the user should hear.
        if (previous == null || previous == current) return
        if (!settings.announceLights) return
        voiceService.announceEvent(
            getString(if (current) R.string.voice_lights_on else R.string.voice_lights_off)
        )
    }

    // --- Periodic voice announcements ---

    private fun startVoiceLoop() {
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            while (true) {
                val settings = settingsRepository.get()
                delay(settings.voiceIntervalSeconds * 1000L)
                if (settings.voiceEnabled && settings.voicePeriodicEnabled) {
                    val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
                    if (settings.voiceOnlyWhenConnected && !connected) continue
                    val data = wheelRepository.wheelData.value
                    voiceService.announceStatus(data, settings, isRecording = tripRepository.recording.value)
                }
            }
        }
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(data: WheelData?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nav = navigationEngine.navState.value
        val text = if (nav.active) {
            // Navigation takes over the notification text so a pocketed phone
            // still shows the next move.
            listOf(nav.primaryText, nav.distanceText)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
                .ifBlank { getString(R.string.nav_title) }
        } else if (data != null && data.speed > 0) {
            val displaySpeed = com.eried.eucplanet.util.Units.speed(data.speed, imperialCached)
            val speedUnit = com.eried.eucplanet.util.Units.speedUnit(this, imperialCached)
            "%.1f %s | %d%% | %.1f V".format(displaySpeed, speedUnit, data.batteryPercent, data.voltage)
        } else {
            val state = wheelRepository.connectionState.value
            state.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private var lastNotificationUpdate = 0L

    private fun updateNotification(data: WheelData) {
        // Throttle to 1 Hz
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < 1000) return
        lastNotificationUpdate = now

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(data))
    }
}
