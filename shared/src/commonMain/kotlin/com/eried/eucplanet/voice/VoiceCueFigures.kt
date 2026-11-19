package com.eried.eucplanet.voice

/**
 * The three sounds of a spoken-command session, as notes (Hz to ms), so both
 * phones play the same figures. Android's TonePlayer and the iPhone's
 * IosCommandCues render them through the shared ToneSynth.
 */
object VoiceCueFigures {
    class Figure(val notes: List<Pair<Int, Int>>, val gapMs: Int, val leadPadMs: Int, val volumePct: Int)

    /**
     * The microphone is open: two quick notes going up. Rising and quick
     * reads as an invitation, and nothing a wheel does sounds like it.
     */
    val PROMPT = Figure(listOf(780 to 70, 1040 to 80), gapMs = 45, leadPadMs = 160, volumePct = 100)

    /** It stopped listening: the same two notes, going down. */
    val END = Figure(listOf(1040 to 70, 780 to 80), gapMs = 45, leadPadMs = 20, volumePct = 75)

    /**
     * Nothing matched: two low notes, the second lower still, below both
     * chirps so the three are told apart at speed with wind over them. The
     * 160 ms lead keeps a clipped start off a low note, where it is loudest.
     */
    val ERROR = Figure(listOf(420 to 110, 310 to 150), gapMs = 30, leadPadMs = 160, volumePct = 85)
}
