package com.eried.eucplanet.diagnostics

/**
 * Single-shot BLE command surfaced in the Wheel Diagnostics dialog as a
 * tappable button. Each adapter contributes its own list via
 * [com.eried.eucplanet.ble.WheelAdapter.getDiagnosticCommands].
 *
 * [label] is intentionally short and bytes-derived so a user reporting a
 * result can describe the button by name (e.g. "T6050_0000 toggled the
 * light") and the dev knows exactly which packet that maps to.
 */
data class DiagnosticCommand(
    val label: String,
    val description: String,
    val bytes: ByteArray,
    val category: Category = Category.OTHER
) {
    enum class Category { LIGHT, HORN, MODE, QUERY, RAW, OTHER }

    override fun equals(other: Any?): Boolean =
        other is DiagnosticCommand && label == other.label && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = label.hashCode() * 31 + bytes.contentHashCode()
}
