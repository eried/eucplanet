package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** Installs saved with the old slow reroute defaults move to the new ones once. */
class RerouteRetuneTest {

    private val defaults = AppSettings().advanced

    private fun oldBlob(grace: Int, reroute: Int) =
        SettingsJson.toJson(AppSettings()).apply {
            remove("navRerouteRetuned")
            put("navOffRouteGraceMs", grace)
            put("navRerouteAfterMs", reroute)
        }

    @Test fun oldDefaults_moveToTheNewDefaults() {
        val a = SettingsJson.fromJson(oldBlob(8000, 22000)).advanced
        assertEquals(defaults.navOffRouteGraceMs, a.navOffRouteGraceMs)
        assertEquals(defaults.navRerouteAfterMs, a.navRerouteAfterMs)
    }

    @Test fun aRiderValue_stays() {
        val a = SettingsJson.fromJson(oldBlob(6000, 15000)).advanced
        assertEquals(6000, a.navOffRouteGraceMs)
        assertEquals(15000, a.navRerouteAfterMs)
    }

    @Test fun afterTheRetune_22sIsTheRidersChoice() {
        val j = SettingsJson.toJson(AppSettings()).apply { put("navRerouteAfterMs", 22000) }
        assertEquals(22000, SettingsJson.fromJson(j).advanced.navRerouteAfterMs)
    }

    @Test fun newDefaults_rerouteWithinTenSeconds() {
        assert(defaults.navRerouteAfterMs <= 10_000)
        assert(defaults.navOffRouteGraceMs < defaults.navRerouteAfterMs)
    }
}
