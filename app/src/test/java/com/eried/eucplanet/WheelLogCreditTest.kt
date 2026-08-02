package com.eried.eucplanet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WheelLog (Wheellog/wheellog.android, GPLv3) is the public reverse-engineering
 * reference behind every wheel adapter (KingSong, Begode, Veteran, Ninebot,
 * InMotion), so its credit MUST stay in BOTH the README acknowledgements and
 * the in-app About > Credits screen.
 *
 * It was once deleted by accident (commit e7e61cc4, a Veteran CRC fix that had
 * no business touching credits, stripped the WheelLog line from both places).
 * This guard fails the build if the credit disappears from either file again.
 * If you intentionally reword it, keep the word "WheelLog" and this test passes.
 */
class WheelLogCreditTest {

    /** Locate a repo file by walking up from the test working dir (the :app
     *  module dir under Gradle) until it is found, so the path holds whether
     *  tests run from the module or the repo root. */
    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("Could not locate '$relative' from ${System.getProperty("user.dir")}")
    }

    @Test
    fun `README credits the WheelLog project`() {
        val readme = repoFile("README.md").readText()
        assertTrue(
            "README.md must credit WheelLog (the GPLv3 EUC-protocol reference). " +
                "Do not remove it - reword if needed but keep the name and link.",
            readme.contains("WheelLog", ignoreCase = true) &&
                readme.contains("wheellog.android", ignoreCase = true)
        )
    }

    @Test
    fun `About screen credits the WheelLog project`() {
        val about = repoFile(
            "app/src/main/java/com/eried/eucplanet/ui/dashboard/DashboardScreen.kt"
        ).readText()
        assertTrue(
            "The in-app About > Credits (Resources & libraries) list must credit " +
                "WheelLog. Do not remove it - reword if needed but keep the name.",
            about.contains("WheelLog", ignoreCase = true)
        )
    }
}
