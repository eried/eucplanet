package com.eried.evendarkerbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eried.evendarkerbot.MainActivity
import com.eried.evendarkerbot.R
import com.eried.evendarkerbot.ble.ConnectionState
import com.eried.evendarkerbot.data.model.WheelData
import com.eried.evendarkerbot.data.repository.SettingsRepository
import com.eried.evendarkerbot.data.repository.TripRepository
import com.eried.evendarkerbot.data.repository.WheelRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.absoluteValue

@AndroidEntryPoint
class WheelService : LifecycleService() {

    companion object {
        private const val TAG = "WheelService"
        const val CHANNEL_ID = "wheel_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.eried.evendarkerbot.CONNECT"
        const val ACTION_DISCONNECT = "com.eried.evendarkerbot.DISCONNECT"
        const val ACTION_START_RECORDING = "com.eried.evendarkerbot.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.eried.evendarkerbot.STOP_RECORDING"
        const val EXTRA_ADDRESS = "device_address"

        private const val ALARM_COOLDOWN_MS = 15_000L
    }

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var voiceService: VoiceService
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var automationManager: AutomationManager

    private var vibrator: Vibrator? = null

    // Alarm cooldown tracking
    private var lastSpeedAlarmMs = 0L

    // Voice announcement
    private var voiceJob: Job? = null
    private var lastConnectionState: ConnectionState? = null
    private var hadGpsFix = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        vibrator = getSystemService(Vibrator::class.java)
        voiceService.initialize()

        // Update notification + check alarms + automations on telemetry updates
        lifecycleScope.launch {
            wheelRepository.wheelData.collect { data ->
                updateNotification(data)
                checkAlarms(data)
                val settings = settingsRepository.get()
                automationManager.evaluate(settings)
            }
        }

        // Start periodic voice announcements
        startVoiceLoop()

        // Monitor connection state for announcements + auto-record
        lifecycleScope.launch {
            wheelRepository.connectionState.collect { state ->
                if (lastConnectionState != null && state != lastConnectionState) {
                    val settings = settingsRepository.get()

                    when (state) {
                        ConnectionState.CONNECTED -> {
                            if (settings.announceConnection) {
                                voiceService.announceEvent(getString(R.string.voice_wheel_connected))
                            }
                            // Auto-record: start recording when wheel connects
                            if (settings.autoRecord && !tripRepository.recording.value) {
                                tripRepository.startRecording()
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            // Only announce if we were actually connected (not just reconnect cycling)
                            if (lastConnectionState == ConnectionState.CONNECTED && settings.announceConnection) {
                                voiceService.announceEvent(getString(R.string.voice_wheel_disconnected))
                            }
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

        // Start GPS tracking for trip recording
        tripRepository.startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address != null) {
                    wheelRepository.connect(address)
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
        }

        return START_STICKY
    }

    override fun onDestroy() {
        voiceJob?.cancel()
        voiceService.shutdown()
        tripRepository.stopLocationUpdates()
        lifecycleScope.launch { tripRepository.stopRecording() }
        wheelRepository.disconnect()
        super.onDestroy()
    }

    // --- Alarm logic ---

    private suspend fun checkAlarms(data: WheelData) {
        val now = System.currentTimeMillis()
        val settings = settingsRepository.get()

        // Speed alarm (uses the beep alarm speed threshold)
        if (data.speed.absoluteValue >= settings.alarmSpeedKmh &&
            now - lastSpeedAlarmMs > ALARM_COOLDOWN_MS
        ) {
            lastSpeedAlarmMs = now
            triggerAlarm("Speed", data.speed.absoluteValue)
        }
    }

    private fun triggerAlarm(type: String, value: Float) {
        Log.w(TAG, "Alarm triggered: $type = $value")
        voiceService.announceAlarm(type, value)
        vibrator?.vibrate(
            VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    // --- Periodic voice announcements ---

    private fun startVoiceLoop() {
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            while (true) {
                val settings = settingsRepository.get()
                delay(settings.voiceIntervalSeconds * 1000L)
                if (settings.voiceEnabled) {
                    val data = wheelRepository.wheelData.value
                    // Only announce if wheel is actually moving or recently active
                    if (data.speed.absoluteValue > 1f || data.batteryPercent > 0) {
                        voiceService.announceStatus(data, settings, isRecording = tripRepository.recording.value)
                    }
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

        val text = if (data != null && data.speed > 0) {
            "%.1f km/h | %d%% | %.1f V".format(data.speed, data.batteryPercent, data.voltage)
        } else {
            val state = wheelRepository.connectionState.value
            state.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EUC Planet")
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
