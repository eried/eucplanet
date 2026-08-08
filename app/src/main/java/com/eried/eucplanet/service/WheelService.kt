package com.eried.eucplanet.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eried.eucplanet.MainActivity
import com.eried.eucplanet.R
import com.eried.eucplanet.audio.AudioOutput
import com.eried.eucplanet.audio.EngineSoundEngine
import com.eried.eucplanet.ble.ConnectionState
import com.eried.eucplanet.data.model.AppSettings
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
        /** True while the service is alive - i.e. the rider is actively using
         *  the app (a ride, or a background feature that needs the service).
         *  External-GPS auto-connect reads this (together with AppForeground) so
         *  a background worker wake that re-creates the process never connects
         *  the box. */
        @Volatile
        var isRunning: Boolean = false
            private set
        // Minimum |speed| (km/h) that counts as "in motion" — shared by the
        // auto-record start/stop loop and the "When riding" announcement gate
        // so the two never drift. Small enough to catch a real roll, large
        // enough to ignore sensor jitter at a standstill.
        private const val MOTION_MIN_KMH = 0.1f
        // Bumped to _v2 so the new lock-screen visibility on the channel actually
        // applies: a NotificationChannel's settings are frozen after first
        // creation, so an existing install ignores code changes to the old id.
        const val CHANNEL_ID = "wheel_connection_v2"
        private const val CHANNEL_ID_LEGACY = "wheel_connection"
        const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.eried.eucplanet.CONNECT"
        const val ACTION_DISCONNECT = "com.eried.eucplanet.DISCONNECT"
        const val ACTION_START_RECORDING = "com.eried.eucplanet.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.eried.eucplanet.STOP_RECORDING"
        const val ACTION_START_NAVIGATION = "com.eried.eucplanet.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.eried.eucplanet.STOP_NAVIGATION"
        /** Stop everything AND hard-kill the process as the last step of
         *  onDestroy. The activity uses this for "Stop All" so the rider
         *  doesn't see the app card linger in the OS cached-process pool
         *  after every visible piece is gone. The kill runs from inside
         *  the service's onDestroy so cleanup completes first, on its own
         *  schedule -- no arbitrary delay timer needed. */
        const val ACTION_STOP_ALL_AND_KILL = "com.eried.eucplanet.STOP_ALL_AND_KILL"
        /** Gentle stand-down when the "Keep app running" toggle is turned OFF.
         *  Unlike ACTION_STOP_ALL_AND_KILL this never kills the process and only
         *  stops the service if nothing else still needs it (a live connection,
         *  recording, navigation, HUD link, or always-on voice). */
        const val ACTION_STOP_KEEPALIVE = "com.eried.eucplanet.STOP_KEEPALIVE"
        // Wheel controls fired from notification action buttons.
        const val ACTION_TOGGLE_LIGHT = "com.eried.eucplanet.TOGGLE_LIGHT"
        const val ACTION_TOGGLE_LOCK = "com.eried.eucplanet.TOGGLE_LOCK"
        const val ACTION_HORN = "com.eried.eucplanet.HORN"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_NAME = "device_name"
        /** Marks an ACTION_CONNECT as auto-initiated (app-start / reconnect)
         *  rather than a wheel the rider picked, so the scan-screen hold can
         *  let user picks through while blocking auto-connects. */
        const val EXTRA_AUTO = "auto_connect"
    }

    @Inject lateinit var wheelRepository: WheelRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    @Volatile
    private var speedUnitCached: String = "kmh"
    // Last paired wheel's name, mirrored from settings so the notification can
    // show it (Tesla-style) without suspending - used when no wheel is actively
    // connected (e.g. while "Connecting" or after a drop).
    @Volatile
    private var lastDeviceNameCached: String? = null
    // Notification quick-action config, mirrored from settings so the
    // notification builder can read it without suspending.
    @Volatile
    private var notifActionsEnabledCached: Boolean = true
    @Volatile
    private var notifActionsCsvCached: String =
        com.eried.eucplanet.data.model.NotificationActionType.DEFAULT_KEYS
    @Inject lateinit var voiceService: VoiceService
    @Inject lateinit var tripRepository: TripRepository
    @Inject lateinit var tripMeterRepository: com.eried.eucplanet.data.repository.TripMeterRepository
    @Inject lateinit var automationManager: AutomationManager
    @Inject lateinit var engineSoundEngine: EngineSoundEngine
    @Inject lateinit var wearBridge: com.eried.eucplanet.wear.WearBridge
    @Inject lateinit var garminBridge: com.eried.eucplanet.garmin.GarminBridge
    @Inject lateinit var externalGpsRepository:
        com.eried.eucplanet.data.repository.ExternalGpsRepository
    @Inject lateinit var navigationEngine: com.eried.eucplanet.nav.NavigationEngine
    @Inject lateinit var hudServer: com.eried.eucplanet.service.hud.HudServer

    // Voice announcement
    private var voiceJob: Job? = null
    // RaceBox-style acceleration splits. Pure tracker fed the telemetry stream in
    // the rider's display speed unit; config re-read each sample so a settings
    // change takes effect without a reconnect. Reset on disconnect.
    private val accelSplitTracker = AccelSplitTracker(increment = 10, minSpeed = 20)
    private var lastConnectionState: ConnectionState? = null
    private var lastLightOn: Boolean? = null
    // Flipped true by ACTION_STOP_ALL_AND_KILL so onDestroy knows to
    // SIGKILL the process at the end of cleanup. Set only once -- never
    // cleared, the process is going away anyway.
    @Volatile
    private var killProcessOnDestroy: Boolean = false
    // Flipped true the instant a teardown begins (Stop All, or onDestroy).
    // Gates every NotificationManager.notify() so a telemetry or nav emission
    // arriving mid-shutdown can't RE-POST the ongoing notification after we
    // removed it -- a re-posted notify() is a standalone notification that
    // outlives the process kill, which is exactly the "Stop All but the
    // notification stays" bug riders reported.
    @Volatile
    private var shuttingDown: Boolean = false

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        val canUseLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val canUseBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

        if (!canUseLocation && !canUseBluetooth) {
            Log.e(TAG, "No permission for either location or bluetooth FGS type, stopping")
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
                handleAccelSplits(data, settings)
            }
        }

        // Apply engine settings + lifecycle on settings changes and connection
        lifecycleScope.launch {
            var hudWasOn = false
            settingsRepository.settings.collect { s ->
                // Notification builder reads the speed unit without suspending;
                // mirror the latest value here every settings update.
                speedUnitCached = com.eried.eucplanet.util.Units.effectiveSpeedUnit(s)
                lastDeviceNameCached = s.lastDeviceName
                notifActionsEnabledCached = s.notificationActionsEnabled
                notifActionsCsvCached = s.notificationActions
                engineSoundEngine.applySettings(s)
                engineSoundEngine.setConnected(
                    wheelRepository.connectionState.value == ConnectionState.CONNECTED,
                    s
                )
                // HUD server lives only while the toggle is on AND the service
                // is running. Watching the settings flow rather than checking
                // once at onCreate so the rider can toggle it mid-session.
                // Debug prop `debug.eucplanet.hud.force=true` bypasses the
                // toggle for emulator testing, where finding the Compose
                // switch coordinates over adb is painful. No effect on real
                // devices, which never have this prop set.
                val forceOn = com.eried.eucplanet.hud.protocol.HudDebug
                    .read("debug.eucplanet.hud.force") == "true"
                val effective = s.hudServerEnabled || forceOn
                if (effective != hudWasOn) {
                    if (effective) hudServer.start() else hudServer.stop()
                    hudWasOn = effective
                }
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
                            // Fresh connection: clear any session suspension of auto-lights
                            automationManager.clearLightsSuspension()
                            if (settings.announceConnection) {
                                voiceService.announceEvent(getString(R.string.voice_wheel_connected))
                            }
                            // Auto-record: start recording when wheel connects, unless the user
                            // gated it on "start in motion"; then the telemetry handler starts it.
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
                            // Speed-based auto-volume drives STREAM_MUSIC down at
                            // standstill; put the rider's baseline back on
                            // disconnect so it isn't left turned down.
                            automationManager.restoreBaselineVolume()
                            automationManager.resetMediaControl()
                            automationManager.resetProximityLock()
                            // Drop any in-flight run + session history so a fresh
                            // ride starts clean and a stale timestamp gap can't
                            // fabricate a summary on reconnect.
                            accelSplitTracker.hardReset()
                        }
                        else -> {}
                    }
                }
                lastConnectionState = state
            }
        }

        // GPS voice: speak only GENUINE satellite acquired/lost events. The
        // TripRepository signal state machine emits these and never the app's own
        // power management, so opening the app, connecting a wheel, and the idle
        // power-down all stay silent.
        lifecycleScope.launch {
            tripRepository.gpsSignalEvents.collect { event ->
                if (!settingsRepository.get().announceGps) return@collect
                val res = when (event) {
                    com.eried.eucplanet.data.model.GpsSignalEvent.ACQUIRED -> R.string.voice_gps_acquired
                    com.eried.eucplanet.data.model.GpsSignalEvent.LOST -> R.string.voice_gps_lost
                }
                voiceService.announceEvent(getString(res))
            }
        }

        // Start GPS tracking for trip recording (only if permission granted)
        if (canUseLocation) {
            tripRepository.startLocationUpdates()
        } else {
            Log.w(TAG, "Location permission not granted, GPS tracking disabled")
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
                if (!shuttingDown &&
                    (nav.active != lastNavActive || now - lastNavNotifyMs >= 1000L)) {
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
                // Hard guard against starting a BLE connection before the
                // rider has granted BLUETOOTH_CONNECT. BluetoothGatt's
                // binder will throw a SecurityException straight out of
                // native code if we proceed without it, taking the whole
                // process down, including any system permission dialog
                // that's currently in front. Bail silently here; the
                // Dashboard's autoConnectIfNeeded already gates on the
                // same permission, this is the belt to its braces.
                val canBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                if (!canBt) {
                    Log.w(TAG, "ACTION_CONNECT before BLUETOOTH_CONNECT granted, skipping")
                    return START_NOT_STICKY
                }
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                val name = intent.getStringExtra(EXTRA_NAME)
                val isAuto = intent.getBooleanExtra(EXTRA_AUTO, false)
                if (address != null) {
                    wheelRepository.connect(address, name, isAuto)
                }
            }
            ACTION_DISCONNECT -> {
                wheelRepository.disconnect()
            }
            ACTION_TOGGLE_LIGHT -> wheelRepository.toggleLight()
            ACTION_TOGGLE_LOCK -> wheelRepository.toggleLock()
            ACTION_HORN -> wheelRepository.sendHorn()
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
            ACTION_STOP_KEEPALIVE -> {
                // "Keep app running" was turned OFF. Stand the service down, but
                // only if nothing else still depends on it. Never kill the
                // process (that's ACTION_STOP_ALL_AND_KILL's job).
                lifecycleScope.launch {
                    val s = settingsRepository.get()
                    val stillNeeded =
                        wheelRepository.connectionState.value != ConnectionState.DISCONNECTED ||
                        tripRepository.recording.value ||
                        navigationEngine.navState.value.active ||
                        s.hudServerEnabled ||
                        (s.voiceEnabled && s.voiceAnnounceWhen == "ALWAYS")
                    if (!s.keepAppAlive && !stillNeeded) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_ALL_AND_KILL -> {
                // Mark first, drop foreground status second, then stopSelf
                // last. Order matters: stopForeground clears the FG flag
                // so START_NOT_STICKY actually keeps Android from
                // resurrecting us; if we skipped that, the SIGKILL at the
                // end of onDestroy looked like a crash and the OS
                // restarted the service (and re-spawned MainActivity off
                // the launcher route, which the rider was seeing as a
                // grey screen + "app reset"). Returning START_NOT_STICKY
                // below seals it: even if Android wanted to redeliver,
                // this intent's stickiness is disabled.
                killProcessOnDestroy = true
                // Stop All clears the running trip meter (car-odometer reset):
                // zero the total and wipe the split log. Block on the write so the
                // wipe is durable before the SIGKILL at the end of onDestroy - a
                // fire-and-forget persist would race the kill and could be lost.
                runCatching {
                    kotlinx.coroutines.runBlocking { tripMeterRepository.resetAndPersist() }
                }
                // Gate notify() BEFORE removing the notification, so a telemetry /
                // nav emission racing this teardown can't re-post it afterwards.
                shuttingDown = true
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    stopForeground(true)
                }
                // Belt-and-braces: explicitly cancel by id in case an update
                // slipped in just before shuttingDown latched.
                getSystemService(NotificationManager::class.java)
                    .cancel(NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        // Any destroy path (not just Stop All) tears the notification down and
        // stops further re-posts, so an ordinary stopSelf() can't leave it behind
        // either.
        shuttingDown = true
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
        // Send one last DataMap so the watch flips to its disconnected
        // ("--") state instantly. If the process is hard-killed and this
        // line never runs, the watch's 3-s stale timer kicks in as
        // fallback. Either way the rider never sees a frozen-stale dial.
        try { wearBridge.publishFarewell() } catch (_: Exception) {}
        try { garminBridge.publishFarewell() } catch (_: Exception) {}
        try { hudServer.stop() } catch (_: Exception) {}
        voiceJob?.cancel()
        engineSoundEngine.stop()
        // Restore the rider's media volume if speed-based auto-volume scaled it
        // down. onDestroy cancels the connection-state collector above, so the
        // disconnect restore may not run on Stop All - do it explicitly here.
        automationManager.restoreBaselineVolume()
        automationManager.resetMediaControl()
        automationManager.resetProximityLock()
        voiceService.shutdown()
        tripRepository.stopLocationUpdates()
        lifecycleScope.launch { tripRepository.stopRecording() }
        wheelRepository.disconnect()
        // On a Stop All (process kill), also close the external GPS (Dragy)
        // GATT first. Without this the Bluetooth stack keeps the connection
        // open for our killed process: the box never powers down (LED stays on,
        // battery drains), the vendor app can't connect, and it looks like EUC
        // Planet "reconnects" because the link was never actually dropped.
        // disconnect() cancels the auto-reconnect loop too, so it stays down.
        // Gated on the kill path so an ordinary teardown (process surviving)
        // leaves the app-scoped GPS connection with the repository that owns it.
        if (killProcessOnDestroy) {
            try { externalGpsRepository.disconnect() } catch (_: Exception) {}
        }
        super.onDestroy()
        // Last thing: if this destroy was driven by Stop All, SIGKILL
        // our own process. Doing it here (instead of from the activity
        // via a delayed Handler) means we kill the moment our cleanup
        // is done -- no arbitrary timer window where the OS keeps the
        // app card around as a cached zombie.
        if (killProcessOnDestroy) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    // --- Auto-record motion gating ---

    // Timestamp of the last sample in motion (|speed| > MOTION_MIN_KMH) while
    // connected. Used by the idle-timeout loop to decide when to auto-stop.
    private var lastMotionAtMs: Long = 0L

    private fun evaluateAutoRecordOnTelemetry(
        data: WheelData,
        settings: com.eried.eucplanet.data.model.AppSettings
    ) {
        if (!settings.autoRecord) return
        val moving = kotlin.math.abs(data.speed) > MOTION_MIN_KMH
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
                val moving = connected && kotlin.math.abs(wheelRepository.wheelData.value.speed) > MOTION_MIN_KMH
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
        // Skip the first observation; that's the state we inherit on connect,
        // not a change the user should hear.
        if (previous == null || previous == current) return
        if (!settings.announceLights) return
        voiceService.announceEvent(
            getString(if (current) R.string.voice_lights_on else R.string.voice_lights_off)
        )
    }

    // --- Periodic voice announcements ---

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun startVoiceLoop() {
        voiceJob?.cancel()
        voiceJob = lifecycleScope.launch {
            while (true) {
                delay(settingsRepository.get().voiceIntervalSeconds * 1000L)
                // Re-read settings *after* the wait, not before it. If the rider
                // turns "Report status periodically" off during the countdown, the
                // pending report is dropped instead of one last one slipping through.
                // Only this periodic report is gated here — alarm/trigger/nav voice
                // lives on separate paths and is unaffected.
                val settings = settingsRepository.get()
                // Gate on the single visible "Enable periodic reports" toggle
                // (voiceEnabled). The old `&& voicePeriodicEnabled` required a
                // second flag that no Settings control ever set - it defaulted
                // false and was only reachable from the dashboard voice menu /
                // wizard, so turning the labelled Settings toggle on produced no
                // periodic reports at all. voicePeriodicEnabled is now retired
                // (the dashboard quick-toggle drives voiceEnabled too).
                if (settings.voiceEnabled) {
                    val connected = wheelRepository.connectionState.value == ConnectionState.CONNECTED
                    val data = wheelRepository.wheelData.value
                    // "RIDING" = the same "in motion" test that auto-starts a trip
                    // recording (shared MOTION_MIN_KMH), so the two stay in lockstep.
                    val moving = connected && kotlin.math.abs(data.speed) > MOTION_MIN_KMH
                    val allowed = when (settings.voiceAnnounceWhen) {
                        "ALWAYS" -> true
                        "RIDING" -> moving
                        else -> connected   // "CONNECTED" + any legacy/unknown value
                    }
                    if (!allowed) continue
                    // Extra opt-in condition: only speak while audio is on an
                    // external output (headphones / Bluetooth / wired / USB).
                    if (settings.voiceAnnounceRequireExternal &&
                        !AudioOutput.isExternalActive(audioManager)) continue
                    voiceService.announceStatus(data, settings, isRecording = tripRepository.recording.value)
                }
            }
        }
    }

    // --- Acceleration splits (RaceBox-style) ---

    private fun handleAccelSplits(data: WheelData, settings: AppSettings) {
        val cfg = settings.accelSplit
        // Fire on the feature's own toggle only, like the other event
        // announcements (connection, GPS, lights). Do NOT also gate on
        // settings.voiceEnabled: that field is the "Report status periodically"
        // switch, so gating here silenced split announcements for any rider who
        // turned periodic reports off while wanting acceleration splits on.
        if (!cfg.enabled) {
            accelSplitTracker.hardReset()
            return
        }
        val unit = com.eried.eucplanet.util.Units.effectiveSpeedUnit(settings)
        val speed = com.eried.eucplanet.util.Units.speed(data.speed, unit).toDouble()
        accelSplitTracker.configure(
            cfg.increment,
            cfg.minSpeed,
            trackAccel = cfg.direction != "BRAKE",
            trackDecel = cfg.direction != "ACCEL",
        )
        // announceEvent queues (QUEUE_ADD) and never drops, so a step crossed
        // while the previous line is still speaking is voiced right after.
        for (s in accelSplitTracker.onSample(data.timestamp, speed)) {
            val text = AccelSplitVoice.splitText(this, s, cfg)
            Log.i(TAG, "accel split: $text")
            com.eried.eucplanet.diagnostics.DiagnosticsLogger.note("accel_split: $text")
            voiceService.announceEvent(text)
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
            // Show the live speed/battery on a secure lock screen instead of
            // "Contents hidden". The user's system "Notifications on lock screen"
            // setting still has the final say.
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val manager = getSystemService(NotificationManager::class.java)
        // Drop the pre-v2 channel so its old (private) lock-screen setting and
        // duplicate entry don't linger in system settings.
        runCatching { manager.deleteNotificationChannel(CHANNEL_ID_LEGACY) }
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
            val displaySpeed = com.eried.eucplanet.util.Units.speed(data.speed, speedUnitCached)
            val speedUnit = com.eried.eucplanet.util.Units.speedUnit(this, speedUnitCached)
            "%.1f %s | %d%% | %.1f V".format(displaySpeed, speedUnit, data.batteryPercent, data.voltage)
        } else {
            val state = wheelRepository.connectionState.value
            state.name.lowercase().replaceFirstChar { it.uppercase() }
        }

        // Title = the wheel's name (Tesla shows the car name here), so the
        // expanded notification doesn't repeat the app name that's already in
        // the header. Prefer the actively-connected name; fall back to the last
        // paired wheel; only show the app name when we've never paired one.
        val wheelName = wheelRepository.connectedDeviceName.value
            ?: lastDeviceNameCached
            ?: getString(R.string.app_name)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(wheelName)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            // Full content on a secure lock screen (pairs with the channel's
            // PUBLIC lockscreenVisibility above).
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // No setSilent(true): IMPORTANCE_LOW already means no sound/peek, and
        // tagging it silent made lock screens set to "hide silent
        // notifications" suppress it entirely.
        buildNotificationActions(nav.active, data).forEach { builder.addAction(it) }
        return builder.build()
    }

    /**
     * The rider's chosen quick-action buttons (up to 3). Labels are context
     * aware - Lock/Unlock, Light on/off, Record/Stop - and STOP_NAV is only
     * shown while navigating (Android can't grey out a notification action).
     */
    private fun buildNotificationActions(
        navActive: Boolean,
        data: WheelData?
    ): List<NotificationCompat.Action> {
        if (!notifActionsEnabledCached) return emptyList()
        val locked = wheelRepository.locked.value
        val lightOn = data?.lightOn ?: false
        val recording = tripRepository.recording.value
        return com.eried.eucplanet.data.model.NotificationActionType
            .parse(notifActionsCsvCached).mapNotNull { type ->
                when (type) {
                    com.eried.eucplanet.data.model.NotificationActionType.STOP_ALL ->
                        notifAction(type, android.R.drawable.ic_menu_close_clear_cancel,
                            getString(R.string.notif_btn_stop_all), ACTION_STOP_ALL_AND_KILL)
                    com.eried.eucplanet.data.model.NotificationActionType.LOCK ->
                        notifAction(type, android.R.drawable.ic_lock_lock,
                            getString(if (locked) R.string.notif_btn_unlock else R.string.notif_btn_lock),
                            ACTION_TOGGLE_LOCK)
                    com.eried.eucplanet.data.model.NotificationActionType.LIGHT ->
                        notifAction(type, android.R.drawable.ic_menu_view,
                            getString(if (lightOn) R.string.notif_btn_light_off else R.string.notif_btn_light_on),
                            ACTION_TOGGLE_LIGHT)
                    com.eried.eucplanet.data.model.NotificationActionType.RECORD ->
                        notifAction(type, android.R.drawable.ic_menu_save,
                            getString(if (recording) R.string.notif_btn_stop_record else R.string.notif_btn_record),
                            if (recording) ACTION_STOP_RECORDING else ACTION_START_RECORDING)
                    com.eried.eucplanet.data.model.NotificationActionType.HORN ->
                        notifAction(type, android.R.drawable.ic_lock_silent_mode_off,
                            getString(R.string.notif_btn_horn), ACTION_HORN)
                    com.eried.eucplanet.data.model.NotificationActionType.DISCONNECT ->
                        notifAction(type, android.R.drawable.ic_menu_close_clear_cancel,
                            getString(R.string.notif_btn_disconnect), ACTION_DISCONNECT)
                    com.eried.eucplanet.data.model.NotificationActionType.STOP_NAV ->
                        if (navActive) notifAction(type, android.R.drawable.ic_menu_directions,
                            getString(R.string.notif_btn_stop_nav), ACTION_STOP_NAVIGATION) else null
                }
            }
    }

    private fun notifAction(
        type: com.eried.eucplanet.data.model.NotificationActionType,
        icon: Int,
        label: String,
        action: String
    ): NotificationCompat.Action {
        val pi = PendingIntent.getService(
            this, 200 + type.ordinal,
            Intent(this, WheelService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(icon, label, pi).build()
    }

    private var lastNotificationUpdate = 0L

    private fun updateNotification(data: WheelData) {
        // Never re-post once teardown has begun: a notify() after
        // stopForeground(REMOVE) creates a standalone notification that survives
        // the process kill, leaving the notification stuck after Stop All.
        if (shuttingDown) return
        // Throttle to 1 Hz
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdate < 1000) return
        lastNotificationUpdate = now

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(data))
    }
}
