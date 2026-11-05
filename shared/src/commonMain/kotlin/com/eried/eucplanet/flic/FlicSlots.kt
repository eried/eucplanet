package com.eried.eucplanet.flic

import com.eried.eucplanet.data.model.AppSettings

/**
 * The four Flic slots in the settings (flic1..flic4): which button sits in
 * which, and what a press on it does. Bound by Bluetooth address, which is
 * the same for a button on both phones, so bindings follow settings.json.
 */
object FlicSlots {
    /** [addr] in the first free slot; null when it already has one or all are full. */
    fun assign(s: AppSettings, addr: String): AppSettings? {
        // Skip if this button is already assigned to any slot.
        if (addr == s.flic1Address || addr == s.flic2Address ||
            addr == s.flic3Address || addr == s.flic4Address) return null
        return when {
            s.flic1Address == null -> s.copy(flic1Address = addr)
            s.flic2Address == null -> s.copy(flic2Address = addr)
            s.flic3Address == null -> s.copy(flic3Address = addr)
            s.flic4Address == null -> s.copy(flic4Address = addr)
            else -> null  // all slots full
        }
    }

    /** Whether [addr] is in a slot. */
    fun isKnown(s: AppSettings, addr: String): Boolean =
        addr == s.flic1Address || addr == s.flic2Address ||
            addr == s.flic3Address || addr == s.flic4Address

    /** Slots emptied for buttons the SDK no longer has, and the SDK's new ones given slots. */
    fun reconcile(settings: AppSettings, addrs: Set<String>): AppSettings {
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
        return updated
    }

    /** The action key bound to [gesture] ("click", "doubleClick", "hold") on [addr], or null. */
    fun actionFor(settings: AppSettings, addr: String, gesture: String): String? {
        val (click, dbl, hold) = when (addr) {
            settings.flic1Address -> Triple(settings.flic1Click, settings.flic1DoubleClick, settings.flic1Hold)
            settings.flic2Address -> Triple(settings.flic2Click, settings.flic2DoubleClick, settings.flic2Hold)
            settings.flic3Address -> Triple(settings.flic3Click, settings.flic3DoubleClick, settings.flic3Hold)
            settings.flic4Address -> Triple(settings.flic4Click, settings.flic4DoubleClick, settings.flic4Hold)
            else -> return null
        }
        return when (gesture) {
            "click" -> click
            "doubleClick" -> dbl
            "hold" -> hold
            else -> null
        }
    }
}
