package com.eried.eucplanet.service.hud

import android.util.Log
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.hud.protocol.HudCommand
import com.eried.eucplanet.hud.protocol.VersionCompat
import com.eried.eucplanet.nav.NavigationEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Routes the small set of commands the HUD remote can issue into the same
 * repositories the phone UI uses. Lives in its own class so [HudServer]
 * doesn't reach across the dependency graph; everything that touches state
 * goes through here.
 *
 * Also captures the HUD's reported protocol version (delivered as the first
 * [HudCommand.Pair] after each WebSocket open) and exposes a flow the
 * Settings UI subscribes to so it can show an "update HUD" hint when the
 * HUD is behind, or "update phone" when WE are.
 */
@Singleton
class HudCommandSink @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val navigationEngine: NavigationEngine,
    private val settingsRepository: SettingsRepository,
    private val flicManager: FlicManager
) {
    companion object { private const val TAG = "HudCommandSink" }

    // Reading the rider's joystick bindings is a suspend call (settings live in
    // DataStore), so HudCommand.Action handling hops onto a coroutine. Main
    // immediate keeps the common "already on main" dispatch synchronous-ish and
    // matches where HudServer delivers commands from.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _hudVersionCompat = MutableStateFlow(VersionCompat.EXACT)
    /** Phone's classification of the connected HUD's protocol version
     *  against ours. EXACT until a HUD has paired. Reset to EXACT by
     *  [HudServer] when the link drops so the UI doesn't show a stale
     *  hint after the HUD goes away. */
    val hudVersionCompat: StateFlow<VersionCompat> = _hudVersionCompat.asStateFlow()

    /** True once a HUD has paired at least once this session. Stays true
     *  through disconnects so the "Need the HUD app?" install hint
     *  doesn't blink back on every time the WebSocket bounces -- the
     *  rider has already proved they have the HUD installed. */
    private val _hudEverConnected = MutableStateFlow(false)
    val hudEverConnected: StateFlow<Boolean> = _hudEverConnected.asStateFlow()

    private val _hudVersion = MutableStateFlow<String?>(null)
    /** APK version string the HUD reported in its Pair message, e.g.
     *  "0.1.6". Null until paired. Used in the UI hint copy and in
     *  diagnostics. */
    val hudVersion: StateFlow<String?> = _hudVersion.asStateFlow()

    /** Called by [HudServer] on every WS open / close so the UI hint
     *  doesn't linger after the HUD has disconnected. */
    fun onHudDisconnected() {
        _hudVersionCompat.value = VersionCompat.EXACT
        _hudVersion.value = null
    }

    fun dispatch(cmd: HudCommand) {
        when (cmd) {
            is HudCommand.Pair -> {
                Log.i(TAG, "HUD paired: id=${cmd.hudId} v=${cmd.hudVersion} " +
                    "proto=${cmd.hudProtocolMajor}.${cmd.hudProtocolMinor}")
                // Older HUD APKs sent Pair without protocol fields; the
                // defaults are 0/0, which we treat as "pre-split 1.0".
                val major = if (cmd.hudProtocolMajor == 0) 1 else cmd.hudProtocolMajor
                _hudVersionCompat.value = VersionCompat.classify(major, cmd.hudProtocolMinor)
                _hudVersion.value = cmd.hudVersion.ifBlank { null }
                _hudEverConnected.value = true
            }
            HudCommand.ToggleLight -> {
                Log.i(TAG, "HUD command: ToggleLight")
                wheelRepository.toggleLight()
            }
            HudCommand.Horn -> {
                Log.i(TAG, "HUD command: Horn")
                wheelRepository.sendHorn()
            }
            HudCommand.StopNavigation -> {
                Log.i(TAG, "HUD command: StopNavigation")
                navigationEngine.stop()
            }
            is HudCommand.Action -> {
                // The slot ("UP"/"DOWN"/"LEFT"/"RIGHT") maps to a rider-configured
                // ActionCatalog key stored on the phone, just like Flic / Volume /
                // Wear. We read the binding and fire it eyes-free through FlicManager,
                // which already handles "" / "NONE" as no-ops.
                scope.launch {
                    val settings = settingsRepository.get()
                    val key = when (cmd.slot) {
                        "UP" -> settings.hudActionUp
                        "DOWN" -> settings.hudActionDown
                        "LEFT" -> settings.hudActionLeft
                        "RIGHT" -> settings.hudActionRight
                        else -> {
                            Log.w(TAG, "HUD command: Action unknown slot=${cmd.slot}")
                            return@launch
                        }
                    }
                    Log.i(TAG, "HUD command: Action slot=${cmd.slot} -> $key")
                    flicManager.dispatchActionByName(key)
                }
            }
        }
    }
}
