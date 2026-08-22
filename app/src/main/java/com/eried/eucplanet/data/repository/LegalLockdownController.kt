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
 * only reads [armed] or calls [isArmed].
 *
 * The side effects of arming (finalising the trip, stopping the recorder,
 * forcing legal mode on) deliberately do NOT live here. Putting them here would
 * make this a hub that depends on half the app and cannot be constructed in a
 * test. The caller in the settings layer runs them, then calls [arm].
 */
@Singleton
class LegalLockdownController @Inject constructor(
    private val store: LegalLockdownStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    init {
        scope.launch {
            store.state.collect { s -> _armed.value = s.armed }
        }
    }

    /**
     * Hot read for the non-suspending callers on the telemetry path (the gates
     * in FlicManager, WheelRepository, WheelService and AutomationManager). The
     * StateFlow is seeded from disk at process start, so this never blocks.
     */
    fun isArmed(): Boolean = _armed.value

    /** Returns false and changes nothing when [pin] is not 4 to 8 digits. */
    suspend fun arm(pin: String): Boolean {
        if (!LegalLockdownCode.isValidPin(pin)) return false
        store.set(armed = true, codeHash = LegalLockdownCode.hash(pin))
        return true
    }

    /** Returns true and unlocks only on a matching code. */
    suspend fun tryDisarm(pin: String): Boolean {
        val current = store.get()
        if (!LegalLockdownCode.matches(pin, current.codeHash)) return false
        // The hash is kept so arming again later can reuse the same code. It is
        // never read while disarmed, and clearing it would buy nothing.
        store.set(armed = false, codeHash = current.codeHash)
        return true
    }
}
