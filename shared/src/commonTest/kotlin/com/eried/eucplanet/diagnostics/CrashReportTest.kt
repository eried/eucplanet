package com.eried.eucplanet.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Both phones name and lay out a crash log and a Service Mode log the same
 * way, so the owner's tools read the files from either.
 */
class CrashReportTest {

    @Test fun namesMatchAndroidsFiles() {
        assertEquals("crash_20261110_141502.txt", CrashReport.fileName("20261110_141502"))
        assertEquals("EUCPlanet-crashlog-20261110_141502.txt", CrashReport.publicName("20261110_141502"))
        assertTrue(Regex("""\d{8}_\d{6}""").matches(CrashReport.stamp(1_700_000_000_000L)))
        assertTrue(
            Regex("""diagnostics-\d{8}-\d{6}\.txt""")
                .matches(WheelDiagnosticsViewModel.shareFileName(1_700_000_000_000L))
        )
    }

    @Test fun theHeaderThenABlankLineThenTheTrace() {
        val text = CrashReport.report(listOf("Time: x", "Thread: main"), "java.lang.IllegalStateException: boom\n\tat a.b(c)")
        assertEquals("Time: x\nThread: main\n\njava.lang.IllegalStateException: boom\n\tat a.b(c)", text)
    }

    @Test fun retentionKeepsTheNewestTwenty() {
        val files = (1..25).map { "crash_$it.txt" to it.toLong() } + ("notes.md" to 0L)
        val gone = CrashReport.beyondRetention(files)
        assertEquals((1..5).map { "crash_$it.txt" }.toSet(), gone.toSet())
        assertTrue(CrashReport.beyondRetention(files.take(20)).isEmpty())
    }
}
