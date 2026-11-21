package com.eried.eucplanet.service.hud.engo

/** Glasses a pairing scan found: where to reach them, what they call
 *  themselves, and how strong the signal is (dBm). */
data class EngoFound(val address: String, val name: String, val rssi: Int)
