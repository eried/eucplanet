package com.eried.eucplanet.app

import com.eried.eucplanet.ble.BluetoothRadio
import kotlin.test.Test
import kotlin.test.assertEquals

/** The iPhone's "Needs attention" rules (iosWarnings). */
class IosHealthRulesTest {

    private val fine = IosHealthState(
        location = LocationAccess.ALWAYS, locationServicesOn = true, autoRecordOnMotion = true,
        radio = BluetoothRadio.ON, hasWheel = true, notificationsDenied = false, chargeAlertsOn = true,
        hasTrips = true, dropboxLinked = true,
    )

    @Test fun nothingToSayWhenAllIsGranted() {
        assertEquals(emptyList(), iosWarnings(fine))
    }

    @Test fun oneLocationWarningAtATime() {
        assertEquals(
            listOf(IosWarning.LOCATION_SERVICES_OFF),
            iosWarnings(fine.copy(locationServicesOn = false, location = LocationAccess.DENIED)),
        )
        assertEquals(listOf(IosWarning.LOCATION_DENIED), iosWarnings(fine.copy(location = LocationAccess.DENIED)))
        // Not asked yet: the prompt is on its way, nothing to warn about.
        assertEquals(emptyList(), iosWarnings(fine.copy(location = LocationAccess.NOT_ASKED)))
    }

    @Test fun whileInUseMattersOnlyForAutoRecord() {
        val whileInUse = fine.copy(location = LocationAccess.WHILE_IN_USE)
        assertEquals(listOf(IosWarning.LOCATION_NOT_ALWAYS), iosWarnings(whileInUse))
        assertEquals(emptyList(), iosWarnings(whileInUse.copy(autoRecordOnMotion = false)))
    }

    @Test fun bluetooth() {
        assertEquals(listOf(IosWarning.BLUETOOTH_NOT_ALLOWED), iosWarnings(fine.copy(radio = BluetoothRadio.NOT_ALLOWED)))
        assertEquals(listOf(IosWarning.BLUETOOTH_OFF), iosWarnings(fine.copy(radio = BluetoothRadio.OFF)))
        // Off with no wheel to reach, or a phone with no radio (the simulator): quiet.
        assertEquals(emptyList(), iosWarnings(fine.copy(radio = BluetoothRadio.OFF, hasWheel = false)))
        assertEquals(emptyList(), iosWarnings(fine.copy(radio = BluetoothRadio.UNSUPPORTED)))
    }

    @Test fun notificationsOnlyWhenAChargeAlertWantsThem() {
        assertEquals(listOf(IosWarning.NOTIFICATIONS_OFF), iosWarnings(fine.copy(notificationsDenied = true)))
        assertEquals(emptyList(), iosWarnings(fine.copy(notificationsDenied = true, chargeAlertsOn = false)))
    }

    @Test fun tripsWithNoBackup() {
        assertEquals(listOf(IosWarning.NO_BACKUP), iosWarnings(fine.copy(dropboxLinked = false)))
        assertEquals(emptyList(), iosWarnings(fine.copy(dropboxLinked = false, hasTrips = false)))
    }

    @Test fun theIdsAreAndroidsWhereTheWarningIsTheSame() {
        assertEquals("perm.location", IosWarning.LOCATION_DENIED.id)
        assertEquals("location.services-off", IosWarning.LOCATION_SERVICES_OFF.id)
        assertEquals("perm.notifications", IosWarning.NOTIFICATIONS_OFF.id)
        assertEquals("backup.folder", IosWarning.NO_BACKUP.id)
        assertEquals(IosWarning.entries.size, IosWarning.entries.map { it.id }.toSet().size)
    }
}
