package com.eried.eucplanet.hud.net

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the rider's manually-entered phone IP, used when the mDNS auto-pair
 * path is blocked by the phone hotspot (AP/client isolation or multicast
 * filtering on the soft AP).
 *
 * Stored as a plain "host:port" string. Null/blank means "fall back to mDNS".
 */
class HudPeerStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_PEER, null)?.takeIf { it.isNotBlank() }

    fun save(peer: String?) {
        prefs.edit().apply {
            if (peer.isNullOrBlank()) remove(KEY_PEER) else putString(KEY_PEER, peer)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "hud_peer"
        private const val KEY_PEER = "manual_peer"
    }
}
