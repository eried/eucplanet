package com.eried.eucplanet.ble

/**
 * Whether [address] is one Android's Bluetooth stack accepts: six pairs of
 * upper-case hex digits with colons between them.
 *
 * Anything else makes `getRemoteDevice` throw. A saved wheel address can be
 * damaged by a bad restore or a hand-edited backup, and it is used again on
 * every start, so it is checked before it gets that far.
 */
fun isBluetoothAddress(address: String?): Boolean =
    address != null && BLUETOOTH_ADDRESS.matches(address)

private val BLUETOOTH_ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")
