package com.eried.eucplanet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A singleton with a `start()` or `initialize()` is dead until somebody
 * calls it, and Hilt cannot tell: it injects the object happily whether or
 * not the call is there. That is how the watch-map merge switched Flic off
 * (PR #25 replaced `flicManager.initialize()` with another line) with no
 * compile error, no test failure and no log line.
 *
 * [AppStartupTest] pins the list in EucPlanetApp.onCreate by hand. This one
 * is the general rule: every `@Singleton` under app/src/main that declares
 * such a method is called through a property of its type somewhere else, or
 * drives itself from one of its own entry points (EngineSoundEngine starts
 * from setConnected). A new subsystem is covered the day it is written.
 */
class LifecycleWiringTest {

    private val startMethods = listOf("start", "initialize")

    @Test fun `every singleton with a start or initialize is called by someone`() {
        val files = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // Comments are not calls: a commented-out start() must read as missing.
        val sources = files.associateWith { stripComments(it.readText()) }
        val orphans = mutableListOf<String>()
        for ((file, src) in sources) {
            for (m in Regex("@Singleton\\s+(?:open\\s+)?class\\s+(\\w+)").findAll(src)) {
                val cls = m.groupValues[1]
                for (method in startMethods) {
                    if (!Regex("\\bfun $method\\(\\)").containsMatchIn(src)) continue
                    // Drives itself: a bare call from another entry point of
                    // the same class (not the declaration).
                    val selfDriven = Regex("(?<![.\\w])(?:this\\.)?$method\\(\\)").findAll(src)
                        .any { !src.substring(0, it.range.first).trimEnd().endsWith("fun") }
                    val calledElsewhere = sources.any { (other, text) ->
                        other != file && Regex("(\\w+)\\s*:\\s*(?:[\\w.]+\\.)?$cls\\b").findAll(text)
                            .map { it.groupValues[1] }.toSet()
                            .any { prop -> Regex("\\b$prop\\.$method\\(\\)").containsMatchIn(text) }
                    }
                    if (!selfDriven && !calledElsewhere) orphans += "$cls.$method()"
                }
            }
        }
        assertTrue("declared but never called, so the subsystem is dead: $orphans", orphans.isEmpty())
    }
}

/** Drops `// ...` and `/* ... */` so a source scan only sees code. */
internal fun stripComments(src: String): String =
    src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")
