package com.eried.eucplanet.nav

import com.eried.eucplanet.data.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic guards for the chargers/stations layer: Overpass parsing, the
 * route bounding box, and the on-the-way distance filter. No network.
 */
class PoiServiceTest {

    private val line = listOf(
        GeoPoint(59.90, 10.70),
        GeoPoint(59.90, 10.80),
    )

    @Test fun boundingBox_padsAroundRoute() {
        val b = PoiService.routeBoundingBox(line)!!
        // Padded out beyond the raw extremes on every side.
        assertTrue(b.minLat < 59.90)
        assertTrue(b.maxLat > 59.90)
        assertTrue(b.minLng < 10.70)
        assertTrue(b.maxLng > 10.80)
    }

    @Test fun classify_electricChargerRule() {
        fun tags(s: String) = org.json.JSONObject(s)
        assertEquals(PoiKind.CHARGER, PoiService.classify(tags("""{"amenity":"charging_station"}""")))
        // A fuel station counts as a charger ONLY when it offers electricity.
        assertEquals(
            PoiKind.CHARGER,
            PoiService.classify(tags("""{"amenity":"fuel","fuel:electricity":"yes"}"""))
        )
        assertEquals(
            PoiKind.STORE,
            PoiService.classify(tags("""{"amenity":"fuel","fuel:diesel":"yes"}"""))
        )
        assertEquals(PoiKind.STORE, PoiService.classify(tags("""{"shop":"convenience"}""")))
        assertEquals(PoiKind.FOOD, PoiService.classify(tags("""{"amenity":"cafe"}""")))
        assertEquals(PoiKind.REST, PoiService.classify(tags("""{"amenity":"drinking_water"}""")))
        assertEquals(PoiKind.SIGHTS, PoiService.classify(tags("""{"tourism":"viewpoint"}""")))
        assertNull(PoiService.classify(tags("""{"amenity":"bank"}""")))
    }

    @Test fun parseOverpass_mapsKindsNamesAndInfo_electricRule() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":59.90,"lon":10.75,
               "tags":{"amenity":"charging_station","socket:type2":"2","name":"Foo"}},
              {"type":"node","id":2,"lat":59.91,"lon":10.71,
               "tags":{"amenity":"fuel","brand":"Circle K","fuel:diesel":"yes","fuel:octane_95":"yes"}},
              {"type":"node","id":3,"lat":59.92,"lon":10.72,
               "tags":{"amenity":"fuel","name":"Shell EV","fuel:electricity":"yes"}}
            ]}
        """.trimIndent()
        val pois = PoiService.parseOverpass(json)
        assertEquals(3, pois.size)
        val charger = pois.first { it.id == 1L }
        assertEquals(PoiKind.CHARGER, charger.kind)
        assertEquals("Foo", charger.name)
        assertTrue(charger.info.contains("type2"))
        // Plain (non-electric) fuel -> STORE, not charger.
        val fuel = pois.first { it.id == 2L }
        assertEquals(PoiKind.STORE, fuel.kind)
        assertEquals("Circle K", fuel.name) // name falls back to brand
        assertTrue(fuel.info.contains("Diesel"))
        // Electric fuel -> CHARGER.
        assertEquals(PoiKind.CHARGER, pois.first { it.id == 3L }.kind)
    }

    @Test fun parseOverpass_enabledFilter_dropsOtherKinds() {
        val json = """
            {"elements":[
              {"type":"node","id":1,"lat":59.90,"lon":10.75,"tags":{"amenity":"charging_station"}},
              {"type":"node","id":2,"lat":59.91,"lon":10.71,"tags":{"amenity":"cafe"}}
            ]}
        """.trimIndent()
        val onlyChargers = PoiService.parseOverpass(json, setOf(PoiKind.CHARGER))
        assertEquals(listOf(1L), onlyChargers.map { it.id })
    }

    @Test fun filterNearRoute_keepsOnTheWay_dropsFar_sortsNearest() {
        val near = PointOfInterest(1, 59.9008, 10.75, PoiKind.CHARGER, "near")   // ~90 m off
        val mid = PointOfInterest(2, 59.9100, 10.75, PoiKind.STORE, "mid")       // ~1.1 km off
        val far = PointOfInterest(3, 59.9600, 10.75, PoiKind.STORE, "far")       // ~6.7 km off -> dropped
        val kept = PoiService.filterNearRoute(listOf(far, mid, near), line)
        assertEquals(listOf("near", "mid"), kept.map { it.name })
        assertTrue(kept[0].distanceFromRouteM < kept[1].distanceFromRouteM)
        assertTrue(kept[0].distanceFromRouteM > 0.0)
    }
}
