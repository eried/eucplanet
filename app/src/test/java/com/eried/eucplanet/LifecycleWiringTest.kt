package com.eried.eucplanet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A singleton with a `start()` or `initialize()` is dead until somebody
 * calls it, and the DI graph cannot tell: it injects the object happily
 * whether or not the call is there. That is how the watch-map merge switched
 * Flic off (PR #25 replaced `flicManager.initialize()` with another line) with
 * no compile error, no test failure and no log line.
 *
 * [AppStartupTest] pins the list in EucPlanetApp.onCreate by hand. This one
 * is the general rule: every Koin `single` (the di package) whose class under
 * app/src/main declares such a method is called through a property of its type somewhere else, or
 * drives itself from one of its own entry points (EngineSoundEngine starts
 * from setConnected). A new subsystem is covered the day it is written.
 */
class LifecycleWiringTest {

    private val startMethods = listOf("start", "initialize")

    @Test fun `every singleton with a start or initialize is called by someone`() {
        val files = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        // Comments are not calls: a commented-out start() must read as missing.
        val sources = files.associateWith { stripComments(it.readText()) }
        val singletons = koinSingletons(sources)
        // A floor, so a change in how the modules are written cannot leave
        // this scan finding nothing and passing on an empty list.
        assertTrue("only ${singletons.size} Koin singletons found: $singletons", singletons.size >= 60)
        val orphans = mutableListOf<String>()
        for ((file, src) in sources) {
            for (m in Regex("(?<![\\w.])class\\s+(\\w+)").findAll(src)) {
                val cls = m.groupValues[1]
                if (cls !in singletons) continue
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

/**
 * Class names the Koin modules register as `single`: `singleOf(::X)`, and
 * `single { X(...) }` where the lambda opens with the constructor call.
 */
internal fun koinSingletons(sources: Map<File, String>): Set<String> {
    val di = sources.filterKeys { it.parentFile?.name == "di" }.values
    val names = mutableSetOf<String>()
    for (src in di) {
        Regex("singleOf\\(::(\\w+)\\)").findAll(src).forEach { names += it.groupValues[1] }
        Regex("\\bsingle(?:<[^>{]*>)?(?:\\([^)]*\\))?\\s*\\{\\s*(\\w+)\\(").findAll(src)
            .forEach { names += it.groupValues[1] }
    }
    return names
}

/** Drops `// ...` and `/* ... */` so a source scan only sees code. */
internal fun stripComments(src: String): String =
    src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")
