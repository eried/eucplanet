package com.eried.eucplanet.hudlink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * mDNS / Bonjour HUD discovery seam — the iOS counterpart to Android's
 * `HudServer.resolveViaMdns` (JmDNS browse of `_eucplanet._tcp`). When the rider
 * leaves the HUD IP blank, the app starts this and dials whatever it resolves, so
 * the HUD connects with no typing — exactly like Android's blank-IP fallback.
 *
 * The iOS Swift `HudDiscoveryBridge` (NetServiceBrowser) sets [nativeStart] /
 * [nativeStop] at launch and reports the first resolved HUD via [onResolved].
 * Android leaves the hooks null (the Android app has its own JmDNS path).
 */
object HudDiscoveryClient {
    private val _resolved = MutableStateFlow<String?>(null)

    /** `"host:port"` of the first discovered HUD on the LAN, or null. */
    val resolved: StateFlow<String?> = _resolved

    var nativeStart: (() -> Unit)? = null
    var nativeStop: (() -> Unit)? = null

    /** Begin browsing for `_eucplanet._tcp` on the local network. */
    fun start() { nativeStart?.invoke() }

    /** Stop browsing and clear the last result. */
    fun stop() { nativeStop?.invoke(); _resolved.value = null }

    /** Called by the platform when a HUD is resolved to an IPv4 host + port. */
    fun onResolved(host: String, port: Int) { _resolved.value = "$host:$port" }
}
