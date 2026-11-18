package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.model.ChargeStatus
import com.eried.eucplanet.data.repository.sanitized
import com.eried.eucplanet.data.store.SettingsJson
import com.eried.eucplanet.service.ChargeAlertPolicy.Alert
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Charge alert edges the main matrix does not cover: a mark above 80, and a mark moved mid-charge. */
class ChargeAlertEdgesTest {

    /** Feed (percent, mark) frames while charging and collect what fired. */
    private fun charge(frames: List<Pair<Int, Int>>): List<ChargeAlertPolicy.Alert> {
        var st = ChargeAlertPolicy.State()
        val fired = mutableListOf<ChargeAlertPolicy.Alert>()
        frames.forEach { (pct, mark) ->
            val step = ChargeAlertPolicy.step(st, ChargeStatus.Charging, pct, true, true, mark)
            st = step.state
            if (step.alert != Alert.NONE) fired += step.alert
        }
        return fired
    }

    @Test fun `a mark above 80 fires after the 80 alert`() {
        assertEquals(
            listOf(Alert.AT_80, Alert.CUSTOM),
            charge(listOf(70, 85, 92).map { it to 90 }),
        )
    }

    @Test fun `one frame past both the mark and 80 fires the mark, then 80 on the next frame`() {
        // Pinned: the mark wins the frame, 80 follows one frame later. Matches
        // the comment in step(), so not reported as a bug.
        var st = ChargeAlertPolicy.State()
        val a = ChargeAlertPolicy.step(st, ChargeStatus.Charging, 50, true, true, 60).also { st = it.state }.alert
        val b = ChargeAlertPolicy.step(st, ChargeStatus.Charging, 85, true, true, 60).also { st = it.state }.alert
        val c = ChargeAlertPolicy.step(st, ChargeStatus.Charging, 85, true, true, 60).also { st = it.state }.alert
        val d = ChargeAlertPolicy.step(st, ChargeStatus.Charging, 86, true, true, 60).alert
        assertEquals(listOf(Alert.NONE, Alert.CUSTOM, Alert.AT_80, Alert.NONE), listOf(a, b, c, d))
    }

    @Test fun `a mark moved below the level of a charge that started above it does not fire`() {
        // Plugged in at 75 with the mark at 90, then moved to 70.
        assertTrue(charge(listOf(75 to 90, 76 to 90, 77 to 70, 78 to 70)).isEmpty())
    }

    @Test fun `a mark moved below the current level mid-charge fires at once`() {
        // Started at 60 with the mark at 90; at 72 the rider moves it to 65.
        // The pack has passed 65 in this charge, so the rider hears it now, once.
        assertEquals(listOf(Alert.CUSTOM), charge(listOf(60 to 90, 70 to 90, 72 to 65, 73 to 65)))
    }

    @Test fun `the custom level survives a settings file round trip`() {
        val s = AppSettings(chargingNotifyCustom = true, chargingNotifyCustomPct = 45)
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(true, back.chargingNotifyCustom)
        assertEquals(45, back.chargingNotifyCustomPct)
    }

    @Test fun `a stored 80 is read back as 60`() {
        val s = AppSettings(chargingNotifyCustomPct = 80)
        val back = SettingsJson.fromJson(JSONObject(SettingsJson.toJson(s).toString()))
        assertEquals(80, back.chargingNotifyCustomPct)
        assertEquals(ChargeAlertPolicy.CUSTOM_DEFAULT_PCT, back.sanitized().chargingNotifyCustomPct)
        assertEquals(95, AppSettings(chargingNotifyCustomPct = 300).sanitized().chargingNotifyCustomPct)
    }
}
