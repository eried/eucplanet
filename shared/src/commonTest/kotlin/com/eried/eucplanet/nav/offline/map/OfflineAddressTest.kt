package com.eried.eucplanet.nav.offline.map

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

class OfflineAddressTest {
    private fun road(kind: String, name: String) = VectorTile.Feature(2, mapOf("kind" to kind, "name" to name), emptyList())

    @Test fun aStopNearTheTracks_isNotNamedAfterTheRailway() {
        assertNull(OfflineAddress.streetName(road("rail", "Ligne des Invalides à Versailles")))
        assertNull(OfflineAddress.streetName(road("ferry", "Bac de Jumièges")))
        assertEquals("Port Debilly", OfflineAddress.streetName(road("minor_road", "Port Debilly")))
        assertEquals("Quai Branly", OfflineAddress.streetName(road("major_road", "Quai Branly")))
    }
}
