package com.eried.eucplanet.service

import com.eried.eucplanet.data.model.ChargeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The charge alerts, which exist to fire once.
 *
 * Telemetry arrives several times a second and a charger tapers across the same
 * point again and again, so almost every test here is about NOT notifying.
 */
class ChargeAlertPolicyTest {

    private val p = ChargeAlertPolicy

    /** Run a whole charge and collect what it announced. */
    private fun run(
        percents: List<Int>,
        status: (Int) -> ChargeStatus = { ChargeStatus.Charging },
        want80: Boolean = true,
        wantFull: Boolean = true,
    ): List<ChargeAlertPolicy.Alert> {
        var st = ChargeAlertPolicy.State()
        val fired = mutableListOf<ChargeAlertPolicy.Alert>()
        percents.forEach { pct ->
            val step = p.step(st, status(pct), pct, want80, wantFull)
            st = step.state
            if (step.alert != ChargeAlertPolicy.Alert.NONE) fired += step.alert
        }
        return fired
    }

    @Test fun `a normal charge announces the mark once, then full once`() {
        val fired = run(
            (60..100).toList(),
            status = { if (it >= 100) ChargeStatus.Full else ChargeStatus.Charging },
        )
        assertEquals(
            listOf(ChargeAlertPolicy.Alert.AT_80, ChargeAlertPolicy.Alert.FULL),
            fired,
        )
    }

    @Test fun `sitting at the mark does not ping on every frame`() {
        // A charger holding 80 for twenty minutes, several frames a second.
        val fired = run(List(200) { 80 })
        assertTrue("nothing should fire without having been below", fired.isEmpty())
    }

    @Test fun `a pack plugged in above the mark never says it reached it`() {
        // Plugged in at 85%: it never "reaches" 80, so saying so would be a lie.
        val fired = run((85..99).toList())
        assertTrue(fired.isEmpty())
    }

    @Test fun `a taper that dips back under does not fire twice`() {
        // 78, 80, 79, 81, 80... which is what a real charger does near the top.
        val fired = run(listOf(78, 80, 79, 81, 80, 82, 81, 83))
        assertEquals(listOf(ChargeAlertPolicy.Alert.AT_80), fired)
    }

    @Test fun `parking a full wheel on the charger is not a charge completing`() {
        val fired = run(List(20) { 100 }, status = { ChargeStatus.Full })
        assertTrue(fired.isEmpty())
    }

    @Test fun `unplugging and charging again is a new session`() {
        var st = ChargeAlertPolicy.State()
        val fired = mutableListOf<ChargeAlertPolicy.Alert>()
        fun frame(status: ChargeStatus, pct: Int) {
            val step = p.step(st, status, pct, want80 = true, wantFull = true)
            st = step.state
            if (step.alert != ChargeAlertPolicy.Alert.NONE) fired += step.alert
        }
        listOf(70, 78, 81).forEach { frame(ChargeStatus.Charging, it) }
        frame(ChargeStatus.Idle, 81)                    // unplugged, went riding
        listOf(60, 75, 82).forEach { frame(ChargeStatus.Charging, it) }
        assertEquals(
            listOf(ChargeAlertPolicy.Alert.AT_80, ChargeAlertPolicy.Alert.AT_80),
            fired,
        )
    }

    @Test fun `disconnecting clears the session too`() {
        var st = p.step(ChargeAlertPolicy.State(), ChargeStatus.Charging, 70, true, true).state
        assertTrue(st.sawBelowMark)
        st = p.step(st, ChargeStatus.Disconnected, 70, true, true).state
        assertEquals(ChargeAlertPolicy.State(), st)
    }

    @Test fun `a toggle that is off stays quiet but still keeps the books`() {
        // Turning the mark alert on mid-charge must not fire for a mark that
        // was passed while it was off.
        var st = ChargeAlertPolicy.State()
        listOf(70, 79, 85, 90).forEach {
            val step = p.step(st, ChargeStatus.Charging, it, want80 = false, wantFull = false)
            st = step.state
            assertEquals(ChargeAlertPolicy.Alert.NONE, step.alert)
        }
        val late = p.step(st, ChargeStatus.Charging, 95, want80 = true, wantFull = true)
        assertEquals(ChargeAlertPolicy.Alert.NONE, late.alert)
    }

    @Test fun `full is reported even when the mark alert is off`() {
        val fired = run(
            listOf(70, 85, 100),
            status = { if (it >= 100) ChargeStatus.Full else ChargeStatus.Charging },
            want80 = false,
        )
        assertEquals(listOf(ChargeAlertPolicy.Alert.FULL), fired)
    }

    @Test fun `a jump straight past the mark to full reports the completion`() {
        // One frame at 79, the next says Full: only one of the two can fire,
        // and finishing is the one that matters.
        val fired = run(
            listOf(79, 100),
            status = { if (it >= 100) ChargeStatus.Full else ChargeStatus.Charging },
        )
        assertEquals(listOf(ChargeAlertPolicy.Alert.FULL), fired)
    }
}
