package com.eried.eucplanet.hud.protocol

/**
 * The map layers the app can draw, in one place.
 *
 * The same seven were spelled out four times over - the navigator's Leaflet
 * table, trip details' Leaflet table, the Studio's tile fetcher and the HUD's
 * tile cache - which is how a layer ends up on one screen and not another, or
 * credited on one and not another. Everything about a layer now lives on its
 * entry: where the tiles come from, who has to be credited for them, and how
 * far the provider actually renders.
 *
 * Attribution is a field rather than an afterthought because it is a licence
 * term, not decoration. OSM data is ODbL, which requires crediting the
 * contributors on anything produced from it, and OpenTopoMap and CyclOSM add
 * CC-BY-SA on the style itself. A layer with no credit is a layer we are not
 * allowed to show.
 */
object MapLayers {

    /**
     * [urlTemplate] uses Leaflet's placeholders ({z}/{x}/{y}, {s} for the
     * subdomain, {r} for retina), so it can go straight into a tileLayer or be
     * expanded by hand for the canvas renderers.
     */
    data class Layer(
        val id: String,
        val urlTemplate: String,
        val attribution: String,
        /** Same credit for a small canvas. A Studio map element can be a
         *  thumbnail on a phone screen, where the full string would be clipped
         *  and credit nobody. Still names the style author and OSM. */
        val attributionShort: String,
        val maxNativeZoom: Int,
        val subdomains: String = "",
        /** Provider serves no @2x, so Leaflet should fetch a zoom deeper on
         *  dense screens instead of scaling one tile up. */
        val detectRetina: Boolean = false,
        /** Served by volunteers or donations, so a video export - which pulls a
         *  tile burst per frame - is exactly what their fair-use policies ask
         *  applications not to do. */
        val communityHosted: Boolean = false,
    )

    const val OSM = "OSM"
    const val CYCLOSM = "CYCLOSM"
    const val TOPO = "TOPO"
    const val HUMANITARIAN = "HUMANITARIAN"
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"
    const val SATELLITE = "SATELLITE"

    /** Order matches eucviewer's picker, so the two read the same. */
    val ALL: List<Layer> = listOf(
        Layer(
            id = OSM,
            urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attribution = "© OpenStreetMap contributors",
            attributionShort = "© OSM",
            maxNativeZoom = 19,
            detectRetina = true,
        ),
        Layer(
            id = CYCLOSM,
            urlTemplate = "https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
            attribution = "© CyclOSM · © OpenStreetMap contributors",
            attributionShort = "© CyclOSM, OSM",
            maxNativeZoom = 20,
            subdomains = "abc",
            detectRetina = true,
            communityHosted = true,
        ),
        Layer(
            id = TOPO,
            urlTemplate = "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
            attribution = "© OpenTopoMap (CC-BY-SA) · © OpenStreetMap contributors",
            attributionShort = "© OpenTopoMap, OSM",
            maxNativeZoom = 17,
            subdomains = "abc",
            communityHosted = true,
        ),
        Layer(
            id = HUMANITARIAN,
            urlTemplate = "https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",
            attribution = "© HOT · OSM France · © OpenStreetMap contributors",
            attributionShort = "© HOT, OSM",
            maxNativeZoom = 20,
            subdomains = "ab",
            detectRetina = true,
            communityHosted = true,
        ),
        Layer(
            id = LIGHT,
            urlTemplate = "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
            attribution = "© CARTO · © OpenStreetMap contributors",
            attributionShort = "© CARTO, OSM",
            maxNativeZoom = 20,
            subdomains = "abcd",
        ),
        Layer(
            id = DARK,
            urlTemplate = "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png",
            attribution = "© CARTO · © OpenStreetMap contributors",
            attributionShort = "© CARTO, OSM",
            maxNativeZoom = 20,
            subdomains = "abcd",
        ),
        Layer(
            id = SATELLITE,
            urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
                "World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attribution = "Esri · Maxar · Earthstar Geographics",
            attributionShort = "© Esri",
            maxNativeZoom = 19,
        ),
    )

    /**
     * Ids each screen stored before there was one list. Trip details has always
     * written SAT, the Studio STREET, the HUD lowercase Carto slugs. Riders have
     * those saved, so they resolve rather than reset.
     */
    private val ALIASES = mapOf(
        "SAT" to SATELLITE,
        "STREET" to OSM,
        "osm" to OSM,
        "cyclosm" to CYCLOSM,
        "topo" to TOPO,
        "hot" to HUMANITARIAN,
        "satellite" to SATELLITE,
        "voyager" to LIGHT,
        "dark_all" to DARK,
    )

    /** The layer for a stored id, falling back to plain OSM. */
    fun byId(id: String): Layer {
        val canonical = ALIASES[id] ?: id
        return ALL.firstOrNull { it.id == canonical } ?: ALL.first()
    }

    /** Tile URL with the placeholders filled in, for the canvas renderers that
     *  fetch tiles themselves rather than handing a template to Leaflet. */
    fun tileUrl(id: String, z: Int, x: Int, y: Int): String {
        val layer = byId(id)
        val shard = layer.subdomains.firstOrNull()?.toString().orEmpty()
        return layer.urlTemplate
            .replace("{s}", shard)
            .replace("{r}", "")
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
    }
}
