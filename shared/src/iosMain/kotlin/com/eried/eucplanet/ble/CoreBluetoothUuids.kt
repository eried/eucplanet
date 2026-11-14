package com.eried.eucplanet.ble

import platform.CoreBluetooth.CBUUID
import kotlin.uuid.Uuid

/**
 * The full lowercase 128-bit form, as Android's UUID.toString() prints it.
 * CoreBluetooth shortens Bluetooth SIG UUIDs to 4 (or 8) hex digits.
 */
internal fun CBUUID.cbFullUuidString(): String {
    val s = UUIDString
    return when (s.length) {
        4 -> "0000$s-0000-1000-8000-00805F9B34FB"
        8 -> "$s-0000-1000-8000-00805F9B34FB"
        else -> s
    }.lowercase()
}

/** The same, as a Kotlin [Uuid]. */
internal fun CBUUID.cbUuid(): Uuid = Uuid.parse(cbFullUuidString())

/** CoreBluetooth's form of a Kotlin [Uuid]. */
internal fun Uuid.toCBUUID(): CBUUID = CBUUID.UUIDWithString(toString())
