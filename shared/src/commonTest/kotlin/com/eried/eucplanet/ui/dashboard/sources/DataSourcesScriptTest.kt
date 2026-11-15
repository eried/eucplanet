package com.eried.eucplanet.ui.dashboard.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The EUC_SOURCES launch hook's spec, which scripted iPhone runs open the sheet with. */
class DataSourcesScriptTest {

    @Test fun `a source alone opens its tab`() {
        assertEquals(DataSourcesScript(DataSource.RACEBOX), DataSourcesScript.parse("racebox"))
    }

    @Test fun `two sources open the comparison`() {
        assertEquals(DataSourcesScript(DataSource.WHEEL, DataSource.RACEBOX), DataSourcesScript.parse("WHEEL:RACEBOX"))
    }

    @Test fun `calibrate asks after its seconds and apply answers too`() {
        assertEquals(
            DataSourcesScript(DataSource.WHEEL, DataSource.RACEBOX, calibrateAfterMs = 20_000),
            DataSourcesScript.parse("WHEEL:RACEBOX:calibrate=20"),
        )
        assertEquals(
            DataSourcesScript(DataSource.WHEEL, DataSource.PHONE, calibrateAfterMs = 5_000, confirmCalibration = true),
            DataSourcesScript.parse("WHEEL:PHONE:apply=5"),
        )
    }

    @Test fun `an unknown source is no script`() {
        assertNull(DataSourcesScript.parse("CAR"))
    }
}
