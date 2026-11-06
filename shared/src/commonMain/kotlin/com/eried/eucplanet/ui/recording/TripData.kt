package com.eried.eucplanet.ui.recording

data class LatLon(val latitude: Double, val longitude: Double)

data class TripDataPoint(
    val date: String,
    val speed: Float,
    val voltage: Float,
    val temperature: Float,
    val battery: Int,
    val altitude: Float,
    val latitude: Double,
    val longitude: Double,
    val totalMileage: Float,
    /** Phone-derived GPS speed in km/h, parsed from the legacy `GPS speed` column. 0 if absent. */
    val gpsSpeed: Float = 0f,
    /** External-GPS-box speed in km/h (RaceBox). NaN when the row's column was empty. */
    val extGpsSpeed: Float = Float.NaN,
    /** Wheel current in amps, signed (negative = regen braking). NaN when the trip's CSV predates the column or the cell is blank. */
    val current: Float = Float.NaN,
    /** PWM / motor load in percent. NaN when the trip's CSV predates the column or the cell is blank. */
    val pwm: Float = Float.NaN,
    /** Motor torque in Nm, signed. NaN when the trip's CSV predates the column or the cell is blank. */
    val torque: Float = Float.NaN,
    /** Motor phase current in amps, signed. NaN when the trip's CSV predates the column or the cell is blank. */
    val phaseCurrent: Float = Float.NaN,
    /** Raw Extra-column event ("wheel.name=KS-16SZ", "wheel.disconnected=1", ...). Empty on normal rows. */
    val extra: String = ""
)

// One Extra-column event from the trip CSV, with the GPS fix of its row so
// wheel changes can be pinned on the map. isWheelStart marks the first row
// of an identity block (recording start or a mid-ride wheel change).
data class TripExtraEvent(
    val time: String,
    val text: String,
    val lat: Double,
    val lon: Double,
    // Start of an identity block (recording start, a reconnect, or a real wheel
    // swap). isWheelChange narrows that to a genuinely DIFFERENT wheel; a
    // same-wheel reconnect is isWheelStart && !isWheelChange.
    val isWheelStart: Boolean,
    val isWheelChange: Boolean,
    val index: Int,
)

// A mid-ride wheel change ready for the map: [gpsIndex] is the position of
// the change row within the GPS-bearing points (= the coords array the map
// polyline is built from), so the trace can switch colour exactly there.
data class WheelSwitchMarker(
    val gpsIndex: Int,
    val lat: Double,
    val lon: Double,
    val label: String,
    // true = a genuinely different wheel took over here (trace colour switches
    // + purple circle); false = the same wheel reconnected (unbroken trace, a
    // small dot only).
    val isChange: Boolean,
)

// Walks the rows' Extra cells and marks identity-block starts: a block
// begins when wheel.name / wheel.mac appears with no open block or repeats
// a field the open block already has (the recorder re-emits the whole
// block on every reconnect). wheel.disconnected closes the block.
//
// isWheelChange separates a genuinely DIFFERENT wheel from the SAME wheel just
// reconnecting. Because the recorder re-emits the whole identity block on every
// reconnect, a block start alone does not mean the wheel changed - we compare
// the identity VALUES. name is the primary per-device key (it carries the
// device suffix, e.g. Adventure-E0000298); mac backs it up. Same identity = a
// reconnect (one trace colour, dot only); different identity = a real swap.
fun extractExtraEvents(points: List<TripDataPoint>): List<TripExtraEvent> {
    val out = ArrayList<TripExtraEvent>()
    var block: MutableSet<String>? = null
    // Identity VALUES of the open block and the one before it, so a block start
    // can be classed as reconnect (same wheel) vs change (different wheel).
    var openName: String? = null
    var openMac: String? = null
    var prevName: String? = null
    var prevMac: String? = null
    for ((i, p) in points.withIndex()) {
        val text = p.extra.trim()
        if (text.isEmpty()) continue
        val eq = text.indexOf('=')
        val key = if (eq > 0) text.substring(0, eq).trim() else text
        val value = if (eq > 0) text.substring(eq + 1).trim() else ""
        var isStart = false
        var isChange = false
        if (key == "wheel.name" || key == "wheel.mac") {
            val field = key.removePrefix("wheel.")
            if (block == null || block.contains(field)) {
                // A new identity block begins. Roll the just-closed block's
                // identity into prev*, then seed the new one from this row.
                isStart = true
                prevName = openName
                prevMac = openMac
                openName = null
                openMac = null
                block = hashSetOf(field)
                if (field == "name") openName = value else openMac = value
                // Genuine change only when we HAD a previous wheel and this
                // identity differs from it. The first block ever is the ride
                // start, not a change. Compare on the field that opened the
                // block (the recorder emits name first, so that is usually the
                // name - a reliable per-device key).
                isChange = when (field) {
                    "name" -> prevName != null && !prevName.equals(value, ignoreCase = true)
                    else -> prevMac != null && !prevMac.equals(value, ignoreCase = true)
                }
            } else {
                block.add(field)
                if (field == "name") openName = value else openMac = value
            }
        } else if (key == "wheel.disconnected") {
            block = null
        } else if (key.startsWith("wheel.") && block != null) {
            block.add(key.removePrefix("wheel."))
        }
        out.add(TripExtraEvent(timePartOf(p.date), text, p.latitude, p.longitude, isStart, isChange, i))
    }
    return out
}

/** "dd.MM.yyyy HH:mm:ss.SSS" or ISO "...T HH:mm:ss..." -> "HH:mm:ss". */
fun timePartOf(date: String): String {
    val sep = date.indexOfFirst { it == ' ' || it == 'T' }
    if (sep < 0) return date
    val t = date.substring(sep + 1)
    return if (t.length >= 8) t.substring(0, 8) else t
}
