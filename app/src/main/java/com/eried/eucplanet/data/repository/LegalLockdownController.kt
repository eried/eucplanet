package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.store.LegalLockdownStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manufacturer code rules for Legal Mode Lockdown.
 *
 * MD5 is deliberate. The secret is a 4 to 8 digit PIN checked on a phone that
 * may be handling telemetry at 250 ms ticks, and the threat model is a rider,
 * not an offline cracker. What makes guessing impractical is the cooldown on
 * the unlock dialog, not the strength of the hash.
 */
object LegalLockdownCode {

    private val PIN = Regex("^[0-9]{4,8}$")

    fun isValidPin(pin: String): Boolean = PIN.matches(pin)

    fun hash(pin: String): String =
        MessageDigest.getInstance("MD5")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** A blank stored hash never matches, so an unconfigured lock cannot be opened. */
    fun matches(pin: String, storedHash: String): Boolean =
        storedHash.isNotEmpty() && hash(pin) == storedHash
}

/**
 * The single owner of Legal Mode Lockdown state. Every other part of the app
 * only reads [engaged] or calls [isEngaged].
 *
 * Two flags, not one. [armed] is the resident setting the rider switches on: by
 * itself it changes nothing and simply waits. [engaged] is the mode actually
 * running, and it latches the first time legal mode comes on while armed. Only
 * the manufacturer code clears it.
 *
 * The side effects of arming do NOT live here. Putting them here would make this
 * a hub that depends on half the app and cannot be constructed in a test.
 */
@Singleton
class LegalLockdownController @Inject constructor(
    private val store: LegalLockdownStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The resident setting. Armed and waiting, or off. */
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    /** The mode actually running. This is what every gate and the UI key on. */
    private val _engaged = MutableStateFlow(false)
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    init {
        scope.launch {
            store.state.collect { s ->
                _armed.value = s.armed
                _engaged.value = s.engaged
            }
        }
    }

    /**
     * Hot read for the non-suspending callers on the telemetry path (the gates
     * in FlicManager, WheelRepository, WheelService and AutomationManager). The
     * StateFlow is seeded from disk at process start, so this never blocks.
     */
    fun isEngaged(): Boolean = _engaged.value

    fun isArmed(): Boolean = _armed.value

    /**
     * Called by [WheelRepository] every time legal mode turns on.
     *
     * This is the latch. Armed plus legal mode on means the mode is now running,
     * and from here only the code gets out of it.
     */
    fun onLegalModeActivated() {
        if (!_armed.value || _engaged.value) return
        scope.launch { store.setEngaged(true) }
    }

    /**
     * Arms the mode. Returns false and changes nothing when [pin] is not 4 to 8
     * digits.
     *
     * [engageNow] is true when legal mode is already on, in which case there is
     * nothing to wait for and the lock takes effect immediately.
     */
    suspend fun arm(pin: String, engageNow: Boolean): Boolean {
        if (!LegalLockdownCode.isValidPin(pin)) return false
        store.set(armed = true, engaged = engageNow, codeHash = LegalLockdownCode.hash(pin))
        return true
    }

    /**
     * Switches the resident setting back off without a code.
     *
     * Only valid while the mode has not engaged yet. Once it has, the settings
     * screen is unreachable anyway, but the guard keeps the rule in one place.
     */
    suspend fun disarmIfNotEngaged() {
        val current = store.get()
        if (current.engaged) return
        store.set(armed = false, engaged = false, codeHash = current.codeHash)
    }

    /** Returns true and ends the mode only on a matching code. */
    suspend fun tryDisarm(pin: String): Boolean {
        val current = store.get()
        if (!LegalLockdownCode.matches(pin, current.codeHash)) return false
        // The code turns the whole thing off, resident setting included, so the
        // rider is not locked again the moment legal mode comes back on. The
        // hash is kept so arming again can reuse the same code.
        store.set(armed = false, engaged = false, codeHash = current.codeHash)
        return true
    }
}
