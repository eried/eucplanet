package com.eried.eucplanet.map

/**
 * The layer registry ([MapLayers], the HUD protocol's table compiled into
 * shared code by shared/build.gradle.kts) as the Leaflet pages' JS object.
 *
 * Built from the registry rather than written out again, so a layer cannot
 * exist on one map screen and not another, and cannot be drawn anywhere
 * without the credit its licence requires.
 */
object MapLayersJs {
    fun table(): String =
        MapLayers.ALL.joinToString(",", "{", "}") { l ->
            val esc = l.attribution.replace("\\", "\\\\").replace("'", "\'")
            "'${l.id}':{url:'${l.urlTemplate}',ref:'${l.refUrlTemplate}',ref2:'${l.extraRefUrlTemplate}',attr:'$esc'," +
                "maxNative:${l.maxNativeZoom},subs:'${l.subdomains}',retina:${l.detectRetina}}"
        }
}
