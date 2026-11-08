package com.eried.eucplanet.ui.charging

import androidx.compose.runtime.Composable

/**
 * What ChargingMonitorScreen asks of the platform it runs on. Android's is
 * built in the composition (rememberAndroidChargingHost); iOS has its own.
 */
interface ChargingHost {
    /**
     * The Export menu item's destination. The returned function takes a
     * suggested file name; [onPicked] gets the platform's handle for where
     * the CSV goes (Android's picked document, iOS's share sheet), which
     * ChargingMonitorViewModel.saveCsv writes through ChargingCsvFiles.
     */
    @Composable
    fun rememberCsvSaver(onPicked: (String) -> Unit): (String) -> Unit
}
