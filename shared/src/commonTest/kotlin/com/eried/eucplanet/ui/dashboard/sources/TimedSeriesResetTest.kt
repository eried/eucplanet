package com.eried.eucplanet.ui.dashboard.sources

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class TimedSeriesResetTest {
    @Test fun `reset average is live only when a speed has been recorded`() {
        assertFalse(DataSourcesViewModel.TimedSeries().hasNonZeroPoint)
        assertFalse(DataSourcesViewModel.TimedSeries(listOf(1L to 0f, 2L to 0f)).hasNonZeroPoint)
        assertFalse(DataSourcesViewModel.TimedSeries(listOf(1L to Float.NaN)).hasNonZeroPoint)
        assertTrue(DataSourcesViewModel.TimedSeries(listOf(1L to 0f, 2L to 12.5f)).hasNonZeroPoint)
    }
}
