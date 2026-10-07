package com.eried.eucplanet.share

import java.util.Locale

/**
 * The two texts the share menu writes for a one-off position: the plain
 * "lat, lng" pair the Copy item puts on the clipboard, and the Google Maps
 * link the Android share sheet sends.
 *
 * Both are formatted with [Locale.US] on purpose. Everything downstream reads
 * these as machine data: Google Maps parses `?q=` as a decimal pair, and a
 * rider who pastes the coordinates into any map or a message expects dots. On
 * a phone set to German, French or Russian the default locale writes "59,91390",
 * which Maps reads as two extra fields and drops the fix somewhere else, so the
 * separator is pinned rather than left to the device.
 *
 * Five decimals is about a metre at these latitudes, which is finer than a
 * phone fix ever is; more digits only make a longer string to read out.
 */
object ShareLocationText {

    const val DECIMALS = 5

    /** What Copy puts on the clipboard: the pair a rider can paste anywhere. */
    fun coordinates(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.${DECIMALS}f, %.${DECIMALS}f", lat, lng)

    /** What the share sheet sends: a Google Maps query for the same point. */
    fun mapsLink(lat: Double, lng: Double): String =
        "https://maps.google.com/?q=" +
            String.format(Locale.US, "%.${DECIMALS}f,%.${DECIMALS}f", lat, lng)
}
