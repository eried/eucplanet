package com.eried.eucplanet.hud.protocol

import java.security.MessageDigest

/**
 * How the HUD gets its map pictures from the phone.
 *
 * The map and the route are the phone's: it knows the rider's layer, whether
 * there is signal, which offline squares are downloaded and whether the rider
 * prefers them. So the HUD asks the phone for every tile of a layer,
 * `GET /tiles/z/x/y.png?layer=<id>` on [HudDiscovery.TILE_PORT], and draws
 * what comes back. The phone answers with the picture, with [NO_DATA_STATUS]
 * where the provider has nothing that deep, or with a 404.
 *
 * A phone older than [PHONE_DECIDES_MINOR] serves its offline map only, so
 * against one the HUD keeps fetching online tiles itself.
 */
object HudTiles {

    /** First phone protocol minor whose tile server answers for a layer. */
    const val PHONE_DECIDES_MINOR: Int = 24

    /** "The provider has no data this deep here": fetch a coarser zoom. */
    const val NO_DATA_STATUS: Int = 204

    fun phoneDecides(phoneMinor: Int): Boolean = phoneMinor >= PHONE_DECIDES_MINOR

    /** The request path for one tile of [layerId]. */
    fun path(layerId: String, z: Int, x: Int, y: Int): String =
        "/tiles/$z/$x/$y.png?layer=${layerId.lowercase()}"

    private val LAYER = Regex("""[?&]layer=([A-Za-z0-9_]{1,40})(?:[&\s]|$)""")

    /** The layer a request line asks for, or null when it names none (an
     *  older HUD, which wants the offline map as is). */
    fun layerOf(requestLine: String): String? =
        LAYER.find(requestLine)?.groupValues?.get(1)

    private const val NO_DATA_SIZE = 2521
    private const val NO_DATA_MD5 = "f27d9de7f80c13501f470595e327aa6d"

    /** Esri's "Map data not yet available" tile, which it sends instead of
     *  an error for a zoom it has nothing for. */
    fun isNoDataTile(bytes: ByteArray): Boolean {
        if (bytes.size != NO_DATA_SIZE) return false
        val md5 = MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return md5 == NO_DATA_MD5
    }
}
