package com.eried.eucplanet.util

import com.eried.eucplanet.data.model.BatteryPercentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The envelope has to follow the percentage the rider is actually shown.
 *
 * A rider can override the wheel's own percentage, because some wheels report
 * a number that disagrees with what their own display shows. Overriding is a
 * statement that the wheel's number is not to be believed.
 *
 * The live envelope was sampled from the raw frame while the same telemetry
 * copy published the estimated percentage beside it. So the tile, the voice
 * report, the trip recording and the trip's envelope chart all used the
 * override, and the one thing that did not was the alarm the rider set against
 * it: "Battery (est)" fired at the charge the rider had told the app to ignore.
 *
 * The composition happens inside a BLE frame handler that a unit test cannot
 * reach, so the arithmetic is tested with the real classes and the call site is
 * held by reading the source, the same way TpmsScanKindTest holds its branch.
 */
class BatteryEnvelopeFollowsOverrideTest {

    private val bucket = 30_000L

    /** A pack whose voltage says one thing and whose own percentage says another. */
    private val override = BatteryPercentSettings(
        mode = BatteryPercentSettings.MODE_CUSTOM,
        minimumCellVoltageMv = 3300,
        maximumCellVoltageMv = 4200,
        seriesCells = 20,
    )

    @Test fun `the override and the wheel disagree, which is why it exists`() {
        // 20s at 4.0 V per cell. The wheel claims 95%; the curve says lower.
        val shown = BatteryPercentEstimator.estimate(
            voltage = 80f,
            seriesCells = 20,
            settings = override,
            reportedPercent = 95,
        )
        assertNotEquals("the test pack has to actually disagree", 95, shown)
    }

    @Test fun `an envelope fed the shown percentage lands on the shown percentage`() {
        val shown = BatteryPercentEstimator.estimate(
            voltage = 80f,
            seriesCells = 20,
            settings = override,
            reportedPercent = 95,
        )

        val right = LiveBatteryEnvelope()
        right.sample(0, shown.toFloat())
        right.sample(bucket, shown.toFloat())

        val wrong = LiveBatteryEnvelope()
        wrong.sample(0, 95f)
        wrong.sample(bucket, 95f)

        assertEquals(shown.toFloat(), right.value, 0.01f)
        assertEquals(95f, wrong.value, 0.01f)
        // The gap is the size of the mistake: an alarm set at the rider's own
        // scale fires early or late by exactly this much.
        assertNotEquals(right.value, wrong.value)
    }

    @Test fun `with the override off the wheel's own number passes through`() {
        val shown = BatteryPercentEstimator.estimate(
            voltage = 80f,
            seriesCells = 20,
            settings = BatteryPercentSettings(),   // MODE_WHEEL
            reportedPercent = 95,
        )
        assertEquals("the override off must change nothing", 95, shown)
    }

    @Test fun `the frame handler samples the shown percentage, not the raw frame`() {
        val source = File("src/main/java/com/eried/eucplanet/data/repository/WheelRepository.kt")
        assertTrue("WheelRepository.kt not found at ${source.absolutePath}", source.exists())
        val text = source.readText()

        // Worked out once, above the envelope, and read by both.
        assertTrue(
            "the shown percentage must be computed before the envelope is sampled",
            text.indexOf("val shownBatteryPercent = BatteryPercentEstimator.estimate(")
                .let { it >= 0 && it < text.indexOf("batteryEnvelope.sample(") },
        )
        assertTrue(
            "the envelope must be fed the shown percentage",
            Regex("""batteryEnvelope\.sample\(\s*System\.currentTimeMillis\(\),\s*shownBatteryPercent\.toFloat\(\),""")
                .containsMatchIn(text),
        )
        assertTrue(
            "the published percentage must be the same one",
            text.contains("batteryPercent = shownBatteryPercent,"),
        )
        // The bug, stated so it cannot come back: the envelope reading the
        // wheel's own field while the copy publishes the estimate.
        assertEquals(
            "the envelope is being fed the raw frame again",
            0,
            Regex("""batteryEnvelope\.sample\([^)]*result\.data\.batteryPercent""")
                .findAll(text).count(),
        )
    }
}
