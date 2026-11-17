package com.eried.eucplanet.app

import com.eried.eucplanet.data.eucstats.EucStatsServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Which leaderboard server the iPhone app talks to. The simulator must not
 * reach the real one by default: iOS starts the app there with no launch
 * environment too, and test trips went to production that way.
 */
class IosEucStatsBaseUrlTest {
    @Test fun aPhoneWithNothingSetUsesProduction() {
        assertEquals(EucStatsServer.DEFAULT_API_BASE_URL, IosEucStats.pickBaseUrl(null, null, simulator = false))
    }

    @Test fun theSimulatorWithNothingSetUsesTheLocalServer() {
        assertEquals(IosEucStats.SIMULATOR_API_BASE_URL, IosEucStats.pickBaseUrl(null, null, simulator = true))
    }

    @Test fun theEnvironmentWinsOverThePlistAndTheDefault() {
        assertEquals(
            "http://10.0.0.5:9000/api/v1",
            IosEucStats.pickBaseUrl(" http://10.0.0.5:9000/api/v1/ ", "http://plist.example/api", simulator = true),
        )
        assertEquals("http://plist.example/api", IosEucStats.pickBaseUrl(null, "http://plist.example/api", simulator = false))
    }

    @Test fun aValueThatIsNotAUrlFallsBack() {
        assertEquals(IosEucStats.SIMULATOR_API_BASE_URL, IosEucStats.pickBaseUrl("local", null, simulator = true))
        assertEquals(EucStatsServer.DEFAULT_API_BASE_URL, IosEucStats.pickBaseUrl("local", null, simulator = false))
    }

    /** These tests run on the simulator, so this is the app's own answer there. */
    @Test fun thisSimulatorRunDoesNotPointAtProduction() {
        assertFalse(IosEucStats.baseUrl.startsWith(EucStatsServer.DEFAULT_API_BASE_URL))
    }
}
