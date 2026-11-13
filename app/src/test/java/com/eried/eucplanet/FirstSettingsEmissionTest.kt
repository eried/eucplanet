package com.eried.eucplanet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MainActivity does two things once per launch, on the first settings
 * emission: it primes the location stream when the permission is already
 * granted, and it brings settings.language back in line with the locale the
 * system has applied.
 *
 * "First" cannot be read off the activity's own settings copy being empty.
 * onCreate fills that copy synchronously, before the first emission of a cold
 * start is delivered, so such a test is false on exactly the launch the work
 * is meant for.
 */
class FirstSettingsEmissionTest {

    @Test fun `the once per launch work is gated on the first emission itself`() {
        val src = stripComments(File("src/main/java/com/eried/eucplanet/MainActivity.kt").readText())
        val collect = src.indexOf("settingsRepository.settings.collect {")
        assertTrue("the settings collector is gone", collect > 0)
        val seed = src.indexOf("withTimeoutOrNull(700) { settingsRepository.get() }")
        assertTrue("the synchronous seed is gone, this guard needs another look", seed > collect)
        val body = src.substring(collect, seed)
        assertTrue("the once per launch gate is gone", body.contains("if (first) {"))
        val decl = Regex("""val first = ([^\r\n]+)""").find(body)?.groupValues?.get(1).orEmpty()
        assertTrue(
            "first is read off the seeded copy, so it is false on a cold start: $decl",
            decl.isNotBlank() && !decl.contains("_settings"),
        )
        // The flag has to be set outside the collector and cleared inside it.
        val flag = decl.trim()
        assertTrue("$flag is not declared before the collector",
            src.substring(0, collect).contains("var $flag = true"))
        assertTrue("$flag is never cleared", body.contains("$flag = false"))
    }
}
