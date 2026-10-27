package com.eried.eucplanet.flic

import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.eried.eucplanet.R
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.CustomBleCommand
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.TripRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.service.AutomationManager
import com.eried.eucplanet.service.VoiceService
import io.flic.flic2libandroid.Flic2Button
import io.flic.flic2libandroid.Flic2ButtonListener
import io.flic.flic2libandroid.Flic2Manager
import io.flic.flic2libandroid.Flic2ScanCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FlicManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val wheelRepository: WheelRepository,
    private val tripRepository: TripRepository,
    private val voiceService: VoiceService,
    private val automationManager: AutomationManager,
    private val appNotifier: com.eried.eucplanet.util.AppNotifier,
    private val legalLockdown: com.eried.eucplanet.data.repository.LegalLockdownController,
    private val voiceCommands: com.eried.eucplanet.voice.VoiceCommandController
) {
    companion object {
        private const val TAG = "FlicManager"
        /** How long a woken player gets to start before the music app is opened. */
        private const val MUSIC_START_CHECK_MS = 1500L
        /** How long a media browser gets to connect before the app is opened instead. */
        private const val MUSIC_BROWSER_CONNECT_MS = 3000L
        /** How long play through the browser gets to be heard. */
        private const val MUSIC_BROWSER_WAIT_MS = 3000L
        /** How long the browser stays connected after play, so the session can start. */
        private const val MUSIC_BROWSER_HOLD_MS = 10_000L
        /** How often play is pressed again while an opened music app starts up. */
        private const val MUSIC_RETRY_MS = 1000L
        /** Presses after opening the app, enough for a cold start without outstaying it. */
        private const val MUSIC_RETRIES = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flic2Manager: Flic2Manager? = null

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    // Timestamp of last dispatched Flic action. UI uses this to flash a Flic indicator briefly.
    private val _lastActionAt = MutableStateFlow(0L)
    val lastActionAt: StateFlow<Long> = _lastActionAt.asStateFlow()

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _pairedButtons = MutableStateFlow<List<Flic2Button>>(emptyList())
    val pairedButtons: StateFlow<List<Flic2Button>> = _pairedButtons.asStateFlow()

    fun initialize() {
        try {
            flic2Manager = Flic2Manager.initAndGetInstance(context, Handler(Looper.getMainLooper()))
            Log.i(TAG, "Flic2Manager initialized")
            reconnectPairedButtons()
            scope.launch { reconcileSettings() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Flic2Manager", e)
        }
    }

    private fun reconnectPairedButtons() {
        val manager = flic2Manager ?: return
        val buttons = manager.buttons
        _pairedButtons.value = buttons
        for (button in buttons) {
            button.addListener(buttonListener)
            button.connect()
        }
        Log.i(TAG, "Reconnected ${buttons.size} paired buttons")
    }

    // Keep AppSettings.flic1..4Address in sync with the Flic2 lib's paired list.
    // Clears stale addresses (button forgotten outside the app) and auto-fills empty slots
    // with buttons the lib already knows, fixes the case where pairing data was lost but the
    // buttons remain known to the Flic lib.
    private suspend fun reconcileSettings() {
        val addrs = (flic2Manager?.buttons ?: return).map { it.bdAddr }.toSet()
        val settings = settingsRepository.get()
        var updated = settings
        if (updated.flic1Address != null && updated.flic1Address !in addrs) updated = updated.copy(flic1Address = null)
        if (updated.flic2Address != null && updated.flic2Address !in addrs) updated = updated.copy(flic2Address = null)
        if (updated.flic3Address != null && updated.flic3Address !in addrs) updated = updated.copy(flic3Address = null)
        if (updated.flic4Address != null && updated.flic4Address !in addrs) updated = updated.copy(flic4Address = null)
        for (addr in addrs) {
            val alreadyAssigned = addr == updated.flic1Address || addr == updated.flic2Address ||
                    addr == updated.flic3Address || addr == updated.flic4Address
            if (alreadyAssigned) continue
            updated = when {
                updated.flic1Address == null -> updated.copy(flic1Address = addr)
                updated.flic2Address == null -> updated.copy(flic2Address = addr)
                updated.flic3Address == null -> updated.copy(flic3Address = addr)
                updated.flic4Address == null -> updated.copy(flic4Address = addr)
                else -> updated  // all slots full
            }
        }
        if (updated != settings) {
            Log.i(TAG, "Reconciled Flic addresses: 1=${updated.flic1Address} 2=${updated.flic2Address} 3=${updated.flic3Address} 4=${updated.flic4Address}")
            settingsRepository.update(updated)
        }
    }

    fun startScan() {
        val manager = flic2Manager ?: return
        // Flic2's startScan throws when the adapter is off, which would crash
        // the app. Surface it as a status message instead.
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (btManager?.adapter?.isEnabled != true) {
            _scanning.value = false
            _scanStatus.value = context.getString(R.string.scan_bluetooth_off_title)
            return
        }
        _scanning.value = true
        _scanStatus.value = ""

        manager.startScan(object : Flic2ScanCallback {
            override fun onDiscoveredAlreadyPairedButton(button: Flic2Button) {
                // Button is in the Flic2 lib's storage already. Two cases:
                //  (1) It's also saved in our AppSettings (flic1/flic2), just reconnect
                //      silently and keep scanning, otherwise scanning for a second button
                //      would abort the moment the first already-paired one is seen.
                //  (2) Lib knows it but AppSettings lost it (e.g. DB reset), complete the
                //      pair flow so the address gets saved and the UI can leave scanning state.
                Log.i(TAG, "Already paired: ${button.bdAddr}")
                button.addListener(buttonListener)
                button.connect()
                _pairedButtons.value = flic2Manager?.buttons ?: emptyList()
                scope.launch {
                    val s = settingsRepository.get()
                    val knownInApp = button.bdAddr == s.flic1Address || button.bdAddr == s.flic2Address ||
                            button.bdAddr == s.flic3Address || button.bdAddr == s.flic4Address
                    if (!knownInApp) {
                        saveButtonAddress(button.bdAddr)
                        _scanStatus.value = context.getString(
                            R.string.flic_status_paired_fmt,
                            button.name ?: button.bdAddr
                        )
                        _scanning.value = false
                        flic2Manager?.stopScan()
                    }
                    // If known in app, leave the scan running so another button can be added.
                }
            }

            override fun onDiscovered(bdAddr: String) {
                Log.i(TAG, "Discovered: $bdAddr")
                _scanStatus.value = context.getString(R.string.flic_status_found_connecting)
            }

            override fun onConnected() {
                _scanStatus.value = context.getString(R.string.flic_status_connected_pairing)
            }

            override fun onAskToAcceptPairRequest() {
                _scanStatus.value = context.getString(R.string.flic_status_accept_pairing)
            }

            override fun onComplete(result: Int, subCode: Int, button: Flic2Button?) {
                _scanning.value = false
                if (result == Flic2ScanCallback.RESULT_SUCCESS && button != null) {
                    Log.i(TAG, "Paired: ${button.bdAddr} name=${button.name}")
                    _scanStatus.value = context.getString(R.string.flic_status_paired_fmt, button.name ?: button.bdAddr)
                    button.addListener(buttonListener)
                    button.connect()
                    _pairedButtons.value = flic2Manager?.buttons ?: emptyList()

                    // Save button address
                    scope.launch { saveButtonAddress(button.bdAddr) }
                } else {
                    Log.w(TAG, "Scan failed: result=$result subCode=$subCode")
                    _scanStatus.value = context.getString(R.string.flic_status_scan_failed_fmt, result)
                }
            }
        })
    }

    fun stopScan() {
        flic2Manager?.stopScan()
        _scanning.value = false
        _scanStatus.value = ""
    }

    fun forgetButton(button: Flic2Button) {
        button.disconnectOrAbortPendingConnection()
        flic2Manager?.forgetButton(button)
        _pairedButtons.value = flic2Manager?.buttons ?: emptyList()
        scope.launch {
            val s = settingsRepository.get()
            val updated = when (button.bdAddr) {
                s.flic1Address -> s.copy(flic1Address = null)
                s.flic2Address -> s.copy(flic2Address = null)
                s.flic3Address -> s.copy(flic3Address = null)
                s.flic4Address -> s.copy(flic4Address = null)
                else -> s
            }
            if (updated !== s) settingsRepository.update(updated)
        }
    }

    private suspend fun saveButtonAddress(bdAddr: String) {
        val s = settingsRepository.get()
        // Skip if this button is already assigned to any slot.
        if (bdAddr == s.flic1Address || bdAddr == s.flic2Address ||
            bdAddr == s.flic3Address || bdAddr == s.flic4Address) return
        val updated = when {
            s.flic1Address == null -> s.copy(flic1Address = bdAddr)
            s.flic2Address == null -> s.copy(flic2Address = bdAddr)
            s.flic3Address == null -> s.copy(flic3Address = bdAddr)
            s.flic4Address == null -> s.copy(flic4Address = bdAddr)
            else -> return  // all slots full
        }
        settingsRepository.update(updated)
    }

    // --- Button event handling ---

    private val buttonListener = object : Flic2ButtonListener() {
        override fun onButtonSingleOrDoubleClickOrHold(
            button: Flic2Button, wasQueued: Boolean, lastQueued: Boolean,
            timestamp: Long, isSingleClick: Boolean, isDoubleClick: Boolean, isHold: Boolean
        ) {
            val gesture = when {
                isSingleClick -> "click"
                isDoubleClick -> "doubleClick"
                isHold -> "hold"
                else -> return
            }
            Log.i(TAG, "Button ${button.bdAddr}: $gesture")
            scope.launch { dispatchAction(button.bdAddr, gesture) }
        }
    }

    private suspend fun dispatchAction(bdAddr: String, gesture: String) {
        val settings = settingsRepository.get()
        val (click, dbl, hold) = when (bdAddr) {
            settings.flic1Address -> Triple(settings.flic1Click, settings.flic1DoubleClick, settings.flic1Hold)
            settings.flic2Address -> Triple(settings.flic2Click, settings.flic2DoubleClick, settings.flic2Hold)
            settings.flic3Address -> Triple(settings.flic3Click, settings.flic3DoubleClick, settings.flic3Hold)
            settings.flic4Address -> Triple(settings.flic4Click, settings.flic4DoubleClick, settings.flic4Hold)
            else -> return
        }
        val actionName = when (gesture) {
            "click" -> click
            "doubleClick" -> dbl
            "hold" -> hold
            else -> return
        }

        Log.i(TAG, "Dispatching action: $actionName")
        executeAction(actionName, settings)
    }

    fun dispatchActionByName(actionName: String) {
        if (actionName.isEmpty() || actionName == "NONE") return
        scope.launch {
            val settings = settingsRepository.get()
            executeAction(actionName, settings)
        }
    }

    /**
     * Executes the action identified by [key]. Unknown / blank / "NONE" keys
     * are no-ops. The dispatch table below is the SINGLE source of behavior
     * for every physical surface (Flic, volume key, watch), adding a new
     * action means:
     *   1. Append to [com.eried.eucplanet.data.model.ActionCatalog.all] (metadata)
     *   2. Add a branch here (behavior)
     * That's it, no other file in the action layer needs to grow.
     *
     * Dashboard-only actions (OPEN_*, MUTE_ALARMS, RESET_TRIP, TOGGLE_UNITS)
     * route through a different executor in the UI layer because they need
     * navigation / Snackbar / Compose handles this service doesn't have.
     */
    /**
     * Resolve and fire a CUSTOM BLE action (key "B:<uuid>"). Family-gated: a
     * command only fires on a matching connected wheel, so raw user bytes never
     * reach the wrong wheel. No-op (logged) otherwise.
     */
    private fun dispatchCustomBle(key: String, settings: AppSettings) {
        val commands = CustomBleCommand.parseAll(settings.dashboardCustomBle)
        val raw = commands[key]
        val connected = wheelRepository.connectedFamilyId
        val cmd = CustomBleCommand.resolveForDispatch(commands, key, connected)
        if (cmd == null) {
            // Tell the rider WHY the tap did nothing instead of silently
            // dropping. The three failure modes from resolveForDispatch:
            //   1. Unknown key (rider deleted the command? shouldn't happen
            //      from a live tile, but defensive)
            //   2. No frames defined (rider made the tile but never typed
            //      the hex bytes)
            //   3. Family mismatch / not connected
            val reason = when {
                raw == null -> "no such command"
                !raw.isSendable -> "no BLE frames defined"
                connected == null -> "no wheel connected"
                raw.family != connected ->
                    "command is for ${raw.family}, wheel is $connected"
                else -> "unknown"
            }
            Log.i(TAG, "custom BLE $key skipped: $reason")
            appNotifier.post("Custom BLE: $reason")
            return
        }
        Log.i(TAG, "custom BLE $key -> ${cmd.frames.size} frame(s) on ${cmd.family}")
        wheelRepository.sendCustomBle(cmd.frames)
    }

    /**
     * Run a catalog action from somewhere that is not a button.
     *
     * Voice goes through here rather than calling the repositories itself, so
     * a spoken "lights" is gated by exactly what a Flic press is gated by:
     * legal-mode lockdown first, then the catalog's own precondition. A
     * second path would be a second place for those rules to be forgotten.
     */
    suspend fun runAction(key: String) {
        executeAction(key, settingsRepository.get())
    }

    private suspend fun executeAction(key: String, settings: AppSettings) {
        if (key.isEmpty() || key == "NONE") return
        _lastActionAt.value = System.currentTimeMillis()
        Log.d(TAG, "executeAction key=$key")

        // Legal Mode Lockdown, checked first and before anything else.
        //
        // Every eyes-free surface (Flic, volume keys, the watch through
        // PhoneWearListenerService, the HUD through HudCommandSink) and the
        // dashboard tiles funnel through this function, so one allowlist here
        // covers all of them. It sits above the custom-BLE branch because a
        // rider's own raw frames could carry a speed-limit write, and above
        // the catalog precondition because the lock can be armed with no wheel
        // connected: checked after it, a legal-mode hotkey press with no wheel
        // present would give silence instead of the dialog that explains the
        // way out.
        if (legalLockdown.isEngaged() && !LockdownGate.isAllowed(key)) {
            if (LockdownGate.raisesUnlockPrompt(key)) {
                com.eried.eucplanet.ui.lockdown.LockdownPromptBus.request()
            }
            Log.i(TAG, "action $key blocked: legal mode lockdown armed")
            return
        }

        // Shared precondition gate (chokepoint A). Every eyes-free surface
        // funnels here, so this single check covers Flic, volume keys, the
        // dashboard tiles, the watch, the HUD long-press and Garmin `action:`.
        val connected = wheelRepository.connectionState.value ==
            com.eried.eucplanet.ble.ConnectionState.CONNECTED
        if (CustomBleCommand.isCustomBleId(key)) {
            // Custom frames go straight to the wheel; skip + log when there's
            // nothing to send them to.
            if (!connected) {
                Log.i(TAG, "custom BLE $key blocked: precondition not met (connected=false)")
                return
            }
            dispatchCustomBle(key, settings)
            return
        }
        // Catalog-driven gate: enabledReader == false means "must not fire
        // right now". Null reader = always allowed.
        val spec = com.eried.eucplanet.data.model.ActionCatalog.byKey(key)
        val ctx = com.eried.eucplanet.data.model.StatusContext(
            wheel = wheelRepository.wheelData.value,
            tripRecording = tripRepository.recording.value,
            safetyActive = wheelRepository.safetySpeedActive.value,
            connected = connected
        )
        if (spec?.enabledReader?.invoke(ctx) == false) {
            Log.i(TAG, "action $key blocked: precondition not met (connected=$connected)")
            return
        }
        when (key) {
            "HORN" -> wheelRepository.sendHorn()
            "LIGHT_TOGGLE" -> {
                // While locked down the light button must not suspend
                // auto-lights. The mode is temporary, and the rider is never
                // told an automation was overridden by it.
                if (!legalLockdown.isEngaged()) automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
            "LOCK_TOGGLE" -> {
                automationManager.notifyManualLockChange()
                wheelRepository.toggleLock()
            }
            "SAFETY_TOGGLE" -> wheelRepository.toggleSafetySpeed()
            "SAFETY_ON" -> wheelRepository.enableSafetySpeed()
            "SAFETY_OFF" -> wheelRepository.disableSafetySpeed()
            "VOICE_LISTEN" -> {
                // Legal Mode already refused above, for every surface at once.
                // What is left is the microphone: without it there is nothing
                // to listen with, and a rider pressing a button deserves to be
                // told that rather than to hear silence.
                if (!voiceCommands.hasMicPermission()) {
                    appNotifier.post(context.getString(R.string.voice_answer_no_mic_permission))
                } else {
                    voiceCommands.listen()
                }
            }
            "VOICE_ANNOUNCE" -> {
                voiceService.announceTrigger(
                    wheelRepository.wheelData.value, settings,
                    isRecording = tripRepository.recording.value
                )
            }
            "RECORD_TOGGLE" -> {
                if (tripRepository.recording.value) {
                    tripRepository.stopRecording()
                } else {
                    tripRepository.startRecording()
                }
            }
            "RECORD_START" -> {
                if (!tripRepository.recording.value) tripRepository.startRecording()
            }
            "RECORD_STOP" -> {
                if (tripRepository.recording.value) tripRepository.stopRecording()
            }
            "MUSIC_START" -> startMusic()
            // Any other media press is the rider taking over, so a Start
            // music still pressing play must not undo it.
            // Smart: playing pauses, as the plain toggle did; silent starts
            // music the way Start music does, opening the player if it must,
            // which the toggle could not.
            "MEDIA_PLAY_PAUSE" -> {
                musicStart?.cancel()
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (audio.isMusicActive) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else startMusic()
            }
            "MEDIA_PAUSE" -> { musicStart?.cancel(); sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
            "MEDIA_NEXT" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "MEDIA_PREVIOUS" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> Log.w(TAG, "Unknown / unsupported action key for physical surface: $key")
        }
    }

    /**
     * Get music going, whichever app the rider uses.
     *
     * An explicit PLAY rather than the toggle: Android hands it to the last
     * app that played audio, and that app resumes even after it was closed,
     * which is what "start music" means to a rider.
     *
     * The key is not enough when that app is closed: with no media session
     * alive, nothing takes it. So when music is still silent a moment later
     * the app is woken the way a car head unit does it, through its media
     * browser service: connect, take the session it hands back, press play
     * on that. No screen opens, so it also works from a Flic in a pocket.
     * Many apps only let approved callers connect, though, so that can be
     * refused.
     *
     * Then the last resort: the app is opened, and play is pressed on its
     * own media button receiver every second until it plays: a closed app
     * has no session to take the key, and only gets one once it has started,
     * which can take seconds. PLAY is safe to repeat, it never pauses, and
     * the presses end the moment music is heard, so what the rider does in
     * the player after that is left alone. Android 10 and later block that
     * launch from the background unless the app is on screen, so from a
     * pocket it may be refused. It fails quietly into the log, because a
     * press that did nothing is no worse than before.
     *
     * Not MEDIA_PLAY_FROM_SEARCH with an empty query, although Android
     * documents it as "play something": YouTube Music takes it, loads an
     * item it calls not playable, and stops.
     *
     * Each step logs under this tag, so a rider's log shows which one worked.
     */
    private var musicStart: kotlinx.coroutines.Job? = null

    private fun startMusic() {
        musicStart?.cancel()
        musicStart = scope.launch {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            Log.i(TAG, "start music: play key sent to the last player")
            kotlinx.coroutines.delay(MUSIC_START_CHECK_MS)
            if (audioManager.isMusicActive) {
                Log.i(TAG, "start music: playing after the play key")
                return@launch
            }
            val selector = android.content.Intent.makeMainSelectorActivity(
                android.content.Intent.ACTION_MAIN,
                android.content.Intent.CATEGORY_APP_MUSIC,
            )
            // "android" is the chooser, standing in when no music app is the default.
            val musicApp = context.packageManager.resolveActivity(selector, 0)
                ?.activityInfo?.packageName?.takeIf { it != "android" }

            if (musicApp != null && playThroughBrowser(musicApp)) {
                repeat((MUSIC_BROWSER_WAIT_MS / 250).toInt()) {
                    if (audioManager.isMusicActive) {
                        Log.i(TAG, "start music: playing through $musicApp's media browser")
                        return@launch
                    }
                    kotlinx.coroutines.delay(250)
                }
                Log.i(TAG, "start music: $musicApp's media browser took play but stayed silent")
            }

            selector.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(selector)
                Log.i(TAG, "start music: opened ${musicApp ?: "the music app"}")
            } catch (e: Exception) {
                Log.i(TAG, "start music: music app not opened: ${e.message}")
                return@launch
            }
            repeat(MUSIC_RETRIES) {
                kotlinx.coroutines.delay(MUSIC_RETRY_MS)
                if (audioManager.isMusicActive) {
                    Log.i(TAG, "start music: playing after opening the app")
                    return@launch
                }
                // To the app just opened, not to whoever played last: that
                // is where the first key went, and it is why we are here.
                if (musicApp == null || !sendMediaKeyTo(musicApp, KeyEvent.KEYCODE_MEDIA_PLAY)) {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            Log.i(TAG, "start music: app opened but never started playing")
        }
    }

    /**
     * Connect to [pkg]'s media browser service and press play on the session
     * it hands back. True when play was pressed, false when the app has no
     * such service, refused the connection or did not answer in time.
     */
    private suspend fun playThroughBrowser(pkg: String): Boolean {
        val service = context.packageManager.queryIntentServices(
            android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0,
        ).firstOrNull()?.serviceInfo
        if (service == null) {
            Log.i(TAG, "start music: $pkg has no media browser service")
            return false
        }
        // The browser calls back on the thread that made it, which needs a looper.
        return kotlinx.coroutines.withContext(Dispatchers.Main) {
            var browser: android.media.browse.MediaBrowser? = null
            val played = kotlinx.coroutines.withTimeoutOrNull(MUSIC_BROWSER_CONNECT_MS) {
                kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    fun finish(ok: Boolean) { if (cont.isActive) cont.resumeWith(Result.success(ok)) }
                    val callback = object : android.media.browse.MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            finish(runCatching {
                                android.media.session.MediaController(context, browser!!.sessionToken)
                                    .transportControls.play()
                            }.isSuccess)
                        }
                        override fun onConnectionFailed() {
                            Log.i(TAG, "start music: $pkg refused the media browser connection")
                            finish(false)
                        }
                        override fun onConnectionSuspended() = finish(false)
                    }
                    browser = android.media.browse.MediaBrowser(
                        context,
                        android.content.ComponentName(service.packageName, service.name),
                        callback,
                        // Asks for the recent root, the one the system's own
                        // playback resumption uses, which apps answer more often.
                        android.os.Bundle().apply {
                            putBoolean(android.service.media.MediaBrowserService.BrowserRoot.EXTRA_RECENT, true)
                        },
                    )
                    runCatching { browser!!.connect() }.onFailure {
                        Log.i(TAG, "start music: media browser connect threw: ${it.message}")
                        finish(false)
                    }
                }
            } ?: false.also { Log.i(TAG, "start music: $pkg's media browser did not answer") }
            if (played) Log.i(TAG, "start music: play pressed through $pkg's media browser")
            // Let the session start before letting go; disconnecting at once
            // can stop a service that was started only for us.
            val b = browser
            scope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(MUSIC_BROWSER_HOLD_MS)
                runCatching { b?.disconnect() }
            }
            played
        }
    }

    /**
     * A media key delivered to one app's own media button receiver, the way
     * a headset press reaches it, instead of to the session Android routes
     * keys to. That session belongs to whichever app played last, often a
     * video app in an error state, and the music app just opened would never
     * hear the key. False when the app has no such receiver.
     */
    private fun sendMediaKeyTo(pkg: String, keyCode: Int): Boolean {
        val probe = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
        val receiver = context.packageManager.queryBroadcastReceivers(probe, 0)
            .firstOrNull()?.activityInfo ?: return false
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)
                    .setClassName(receiver.packageName, receiver.name)
                    .putExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            )
        }
        return true
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }
}

/**
 * What may still fire while Legal Mode Lockdown is armed.
 *
 * An allowlist, not a blocklist, on purpose: an action added to ActionCatalog
 * later is blocked by default rather than silently becoming a hole in the lock.
 */
object LockdownGate {

    /** The two buttons the locked screen offers. Nothing else runs. */
    val ALLOWED_ACTIONS: Set<String> = setOf("HORN", "LIGHT_TOGGLE")

    private val LEGAL_MODE_ACTIONS = setOf("SAFETY_TOGGLE", "SAFETY_ON", "SAFETY_OFF")

    fun isAllowed(key: String): Boolean = key in ALLOWED_ACTIONS

    /** A blocked legal-mode press shows the rider the way out instead of doing nothing. */
    fun raisesUnlockPrompt(key: String): Boolean = key in LEGAL_MODE_ACTIONS
}
