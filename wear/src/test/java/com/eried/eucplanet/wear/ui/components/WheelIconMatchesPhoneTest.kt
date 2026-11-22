package com.eried.eucplanet.wear.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The watch's WheelIcon is a copy of the phone's; its paths must stay the same. */
class WheelIconMatchesPhoneTest {

    /** Walks up from the test working dir until [relative] exists, or null. */
    private fun repoFile(relative: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun paths(file: File): List<String> =
        Regex("\"(M[^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toList()

    @Test
    fun watchIconDrawsThePhoneIconsPaths() {
        val watch = repoFile("wear/src/main/java/com/eried/eucplanet/wear/ui/components/WheelIcon.kt")!!
        // The phone's icon is in the app module, or in shared on the iPhone branch.
        val phone = listOf(
            "app/src/main/java/com/eried/eucplanet/ui/common/WheelIcon.kt",
            "shared/src/commonMain/kotlin/com/eried/eucplanet/ui/common/WheelIcon.kt",
        ).firstNotNullOfOrNull { repoFile(it) }
        assertTrue("phone WheelIcon.kt not found", phone != null)
        assertEquals(paths(phone!!), paths(watch))
    }
}
