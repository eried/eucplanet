package com.eried.eucplanet.data.repository

import org.json.JSONObject

/**
 * One wheel as the change-wheel picker offers it, and as a trip file carries
 * it: the full identity, not just a name.
 *
 * Labelled the way eucviewer labels it, so the two tools file a trip under
 * the same wheel. eucviewer prefers brand and model, falls back to the
 * advertised BLE name, and as a last resort shows the MAC; it then groups by
 * label plus serial or MAC, so two wheels of one model stay apart. Keying
 * the picker on the BLE name alone, as it used to, showed the same physical
 * wheel under three labels across the two tools: the old recorder wrote only
 * a name, the new one writes name, brand, model and MAC, and the wheel had
 * been renamed in between.
 */
data class WheelChoice(
    val name: String? = null,
    val mac: String? = null,
    val brand: String? = null,
    val model: String? = null,
    val serial: String? = null,
) {
    /** eucviewer's labelOfWheel, line for line. */
    val label: String
        get() {
            val modelHasMake = !brand.isNullOrBlank() && !model.isNullOrBlank() &&
                model.lowercase().startsWith(brand.lowercase())
            val byModel = if (modelHasMake) model.orEmpty()
            else listOfNotNull(brand?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
                .joinToString(" ")
            return byModel.ifBlank { name?.takeIf { it.isNotBlank() } ?: mac?.let { "Wheel $it" } ?: "" }
        }

    /** eucviewer's group key: the label plus whatever tells two of the same model apart. */
    val key: String get() = label + "|" + (serial ?: mac ?: "")

    val isEmpty: Boolean get() = label.isBlank()

    /** Extra-column pairs, in eucviewer's order, only for fields that exist. */
    fun extraFields(): Map<String, String> = linkedMapOf<String, String>().apply {
        name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
        mac?.takeIf { it.isNotBlank() }?.let { put("mac", it.replace(":", "").replace("-", "").uppercase()) }
        brand?.takeIf { it.isNotBlank() }?.let { put("brand", it) }
        model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        serial?.takeIf { it.isNotBlank() }?.let { put("serial", it) }
    }

    /** The same JSON shape TripRecord.wheelMetaJson has always used. */
    fun toJson(): String = JSONObject().apply {
        name?.takeIf { it.isNotBlank() }?.let { put("ble_name", it) }
        mac?.takeIf { it.isNotBlank() }?.let { put("ble_mac", it.replace(":", "").replace("-", "").uppercase()) }
        brand?.takeIf { it.isNotBlank() }?.let { put("brand", it) }
        model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
        serial?.takeIf { it.isNotBlank() }?.let { put("serial", it) }
    }.toString()

    companion object {
        fun fromJson(json: String?): WheelChoice? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() }
            val c = WheelChoice(
                name = s("ble_name"), mac = s("ble_mac"), brand = s("brand"),
                model = s("model"), serial = s("serial"),
            )
            return c.takeIf { !it.isEmpty }
        }
    }
}
