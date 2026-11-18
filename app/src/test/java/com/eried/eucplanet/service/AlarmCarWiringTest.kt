package com.eried.eucplanet.service

import androidx.car.app.connection.CarConnection
import com.eried.eucplanet.car.CarAlarmNotification
import com.eried.eucplanet.stripComments
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Alarms on the car screen" promised every firing alarm and delivered only
 * the GPS ones: the hook sat in one of the action paths, and a wheel or radar
 * alarm never reached the car. Nothing fails when a path forgets the call, so
 * this pins all of them.
 */
class AlarmCarWiringTest {

    @Test fun `every alarm action path posts to the car`() {
        val src = stripComments(File("../shared/src/commonMain/kotlin/com/eried/eucplanet/service/AlarmEngine.kt").readText())
        for (path in listOf(
            "executeActions",
            "executeRadarActions",
            "executeExternalGpsActions",
            // A constant tone skips executeActions, so it posts as it starts.
            "handleConstantTone",
        )) {
            // Up to the function's own closing brace.
            val body = src.substringAfter("private fun $path(").substringBefore("\n    }\n")
            assertTrue("$path no longer posts to the car", body.contains("notifyCar(rule, "))
        }
    }

    @Test fun `a card is posted only while a car is attached`() {
        assertFalse(CarAlarmNotification.isAttached(null))
        assertFalse(CarAlarmNotification.isAttached(CarConnection.CONNECTION_TYPE_NOT_CONNECTED))
        assertTrue(CarAlarmNotification.isAttached(CarConnection.CONNECTION_TYPE_PROJECTION))
        assertTrue(CarAlarmNotification.isAttached(CarConnection.CONNECTION_TYPE_NATIVE))
    }
}
