package com.eried.eucplanet.flic

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The phone's music player through media keys and media browsers; moved from FlicManager. */
class AndroidMediaControls(private val context: Context) : MediaControls {
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

    // Smart: playing pauses, as the plain toggle did; silent starts
    // music the way Start music does, opening the player if it must,
    // which the toggle could not.
    override fun playPauseOrStart() {
        musicStart?.cancel()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.isMusicActive) sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) else startMusic()
    }

    // Any other media press is the rider taking over, so a Start
    // music still pressing play must not undo it.
    override fun pause() { musicStart?.cancel(); sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) }
    override fun next() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    override fun previous() = sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

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

    override fun startMusic() {
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
