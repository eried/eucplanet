package com.eried.eucplanet.hud.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip + backwards-compatibility tests for the wire JSON.
 *
 * These tests are the safety net for the "bump rules" in
 * `docs/protocols/motoeye_hud.md`:
 *
 *  - The DEFAULT_FROZEN_V1_JSON snapshot pins what a HUD APK shipped at
 *    PROTOCOL_MAJOR=1, PROTOCOL_MINOR=0 expects to be able to decode. If a
 *    future change breaks that decode, this test fails -- the change is a
 *    MAJOR bump and the docs need updating.
 *
 *  - The ignore-unknown-keys test confirms an older decoder can still read a
 *    frame produced by a newer encoder that added fields. That's the
 *    contract that lets us bump MINOR without churning every existing HUD.
 */
class WireFormatTest {

    /**
     * IMPORTANT: Both the phone and the HUD construct their JSON encoder
     * with these exact flags. The HudState carries Float.NaN for several
     * fields (gpsHeadingDeg, gpsAltitudeM, gpsSpeedKmh); without
     * allowSpecialFloatingPointValues every frame fails to serialise and
     * the link silently delivers nothing.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    @Test fun roundtrip_default_state_is_stable() {
        val original = HudState()
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<HudState>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun roundtrip_populated_state_preserves_all_fields() {
        val original = HudState(
            connected = true,
            wheelName = "InMotion V14",
            speedKmh = 42.5f,
            batteryPercent = 73,
            voltage = 95.2f,
            current = 8.1f,
            pwm = 65.4f,
            temperatureC = 38.0f,
            tripKm = 1234.5f,
            torque = 12.0f,
            lightOn = true,
            unitSpeed = "mph",
            unitDistance = "mi",
            unitTemp = "F",
            accentArgb = "#FF6B6B6B",
            latitude = 60.39,
            longitude = 5.32,
            gpsSpeedKmh = 41.8f,
            gpsSource = "PHONE",
            gpsHasFix = true,
            gpsHeadingDeg = 270f,
            gpsAltitudeM = 4.5f,
            wheelRollDeg = 1.2f,
            wheelPitchDeg = -0.3f,
            customOverlayJson = """{"name":"Cruise"}""",
            navActive = true,
            navArrowAngleDeg = -45f,
            navPrimary = "Turn left onto Storgata",
            navDistance = "120 m",
            navArrived = false,
            timestampMs = 1717180400000L
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<HudState>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun NaN_floats_survive_roundtrip() {
        // GPS-derived fields are NaN-when-absent. The decoder reads them
        // back as NaN; equality requires special handling because
        // Float.NaN != Float.NaN by definition.
        val original = HudState(
            gpsSpeedKmh = Float.NaN,
            gpsHeadingDeg = Float.NaN,
            gpsAltitudeM = Float.NaN
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<HudState>(encoded)
        assertTrue("gpsSpeedKmh should be NaN", decoded.gpsSpeedKmh.isNaN())
        assertTrue("gpsHeadingDeg should be NaN", decoded.gpsHeadingDeg.isNaN())
        assertTrue("gpsAltitudeM should be NaN", decoded.gpsAltitudeM.isNaN())
    }

    @Test fun frozen_v1_0_baseline_still_decodes() {
        // This is a snapshot of what a HUD APK built at PROTOCOL_MAJOR=1
        // PROTOCOL_MINOR=0 sent on the wire. If a future change breaks
        // this decode, you've made a BREAKING change and need to bump
        // PROTOCOL_MAJOR (and update docs/protocols/motoeye_hud.md).
        val decoded = json.decodeFromString<HudState>(DEFAULT_FROZEN_V1_JSON)
        // Sanity check a handful of fields rather than full equality;
        // equality would fail every time we ADD a new field which is
        // exactly the MINOR-bump case we DO want to keep passing.
        assertEquals(1, decoded.protocolMajor)
        assertEquals(0, decoded.protocolMinor)
        assertEquals(42.5f, decoded.speedKmh, 0.001f)
        assertEquals("kmh", decoded.unitSpeed)
        assertEquals("PHONE", decoded.gpsSource)
        assertTrue(decoded.gpsHeadingDeg.isNaN())
    }

    @Test fun frame_with_unknown_field_still_decodes() {
        // Simulates a newer encoder that added a field this decoder does
        // not know about (the typical MINOR-bump scenario from the
        // OLDER side's POV). The decoder must accept the frame and the
        // unknown field is silently dropped.
        val withUnknown = """
            {
              "protocolVersion": 1,
              "protocolMajor": 1,
              "protocolMinor": 4,
              "speedKmh": 33.0,
              "newFutureField": "this is from a HUD we have not shipped yet",
              "anotherOne": 42
            }
        """.trimIndent()
        val decoded = json.decodeFromString<HudState>(withUnknown)
        assertEquals(33.0f, decoded.speedKmh, 0.001f)
        assertEquals(1, decoded.protocolMajor)
        assertEquals(4, decoded.protocolMinor)
    }

    @Test fun pair_command_roundtrips_with_protocol_fields() {
        val original: HudCommand = HudCommand.Pair(
            hudId = "motoeye-e6-7f3a",
            hudVersion = "0.1.6",
            hudProtocolMajor = 1,
            hudProtocolMinor = 0
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<HudCommand>(encoded)
        assertEquals(original, decoded)
    }

    @Test fun pair_command_from_pre_split_HUD_still_decodes() {
        // An older HUD APK built before the MAJOR/MINOR split sent Pair
        // without the version fields. Default 0/0 covers that case.
        val legacyPair = """
            {
              "type": "com.eried.eucplanet.hud.protocol.HudCommand.Pair",
              "hudId": "old-hud",
              "hudVersion": "0.1.5"
            }
        """.trimIndent()
        val decoded = json.decodeFromString<HudCommand>(legacyPair) as HudCommand.Pair
        assertEquals(0, decoded.hudProtocolMajor)
        assertEquals(0, decoded.hudProtocolMinor)
        // The phone-side HudCommandSink bumps 0 to 1 before passing to
        // VersionCompat.classify -- so the rider sees EXACT, not
        // REMOTE_BEHIND_MAJOR, against a 1.x phone.
    }

    companion object {
        /** JSON sample that a phone built at PROTOCOL_MAJOR=1 MINOR=0
         *  would have shipped. Pinned here so future changes can't
         *  silently break the wire compatibility contract. */
        private const val DEFAULT_FROZEN_V1_JSON = """
            {
              "protocolVersion": 1,
              "protocolMajor": 1,
              "protocolMinor": 0,
              "connected": true,
              "wheelName": "Demo Wheel",
              "speedKmh": 42.5,
              "batteryPercent": 73,
              "voltage": 95.2,
              "current": 8.1,
              "pwm": 65.4,
              "temperatureC": 38.0,
              "tripKm": 1234.5,
              "torque": 12.0,
              "lightOn": false,
              "gaugeMaxKmh": 60.0,
              "gaugeOrangeThresholdPct": 80,
              "gaugeRedThresholdPct": 90,
              "showGaugeColorBand": true,
              "unitSpeed": "kmh",
              "unitDistance": "km",
              "unitTemp": "C",
              "accentArgb": "#FF00C853",
              "latitude": 60.39,
              "longitude": 5.32,
              "gpsSpeedKmh": 41.8,
              "gpsSource": "PHONE",
              "gpsHasFix": true,
              "gpsHeadingDeg": NaN,
              "gpsAltitudeM": NaN,
              "wheelRollDeg": 0.0,
              "wheelPitchDeg": 0.0,
              "customOverlayJson": "",
              "navActive": false,
              "navArrowAngleDeg": 0.0,
              "navPrimary": "",
              "navDistance": "",
              "navArrived": false,
              "timestampMs": 1717180400000
            }
        """
    }
}
