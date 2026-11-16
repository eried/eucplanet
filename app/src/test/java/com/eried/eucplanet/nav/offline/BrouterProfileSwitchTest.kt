package com.eried.eucplanet.nav.offline

import btools.router.ProfileCache
import btools.router.RoutingContext
import com.eried.eucplanet.nav.RouteAvoidances
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The avoids reach BRouter as switches its profiles accept. It reads an
 * injected switch as a number: "true" and "false" threw while the profile
 * was read, so a rider with an avoid on never routed offline.
 */
class BrouterProfileSwitchTest {

    private val profiles = File("src/main/assets/brouter")

    private fun read(profile: String, params: Map<String, String>): Throwable? {
        val rc = RoutingContext().apply {
            localFunction = File(profiles, "$profile.brf").absolutePath
            keyValues = HashMap(params + ("turnInstructionMode" to "9"))
            memoryclass = 64
        }
        return try {
            ProfileCache.parseProfile(rc)
            ProfileCache.releaseProfile(rc)
            null
        } catch (t: Throwable) {
            t
        }
    }

    @Test fun everyAvoid_isASwitchTheProfilesRead() {
        val all = BrouterMapping.avoidParams(RouteAvoidances(highways = true, ferries = true, unpaved = true))
        for (profile in listOf("wheel", "hiking-mountain")) {
            val failure = read(profile, all)
            assertTrue("$profile with $all: $failure", failure == null)
        }
    }

    @Test fun aWordForASwitch_failsTheProfile() {
        // What the app used to send.
        assertTrue(read("wheel", mapOf("avoid_unsafe" to "true")) != null)
    }
}
