package com.eried.eucplanet.ui.about

/** name | reason row for the credits / resources tables. */
internal data class CreditEntry(val name: String, val reason: String)

/**
 * Static About content, ported verbatim from the Android About (Credits tab +
 * License tab). The Resources list reflects the libraries the iOS port actually
 * uses (Compose Multiplatform / Ktor / KMP) rather than Android-only ones, while
 * keeping the WheelLog + BigSoundBank credits — being honest about what's used.
 */
internal object AboutContent {
    const val MADE_BY_BODY =
        "I got tired of EUC apps that evolve too slowly, look dated, have draconian rules around new " +
            "features, treat their communities as customers, and forget that EUC riders are at their best " +
            "when they help each other. This one tries to be the opposite."

    val THANKS = listOf(
        CreditEntry("Gio (Wheel In Motion)", "Promotion, suggestions and P6 testing. Stitched scalp, intact enthusiasm."),
        CreditEntry("FlyboyEUC (Adam)", "Mten3, E20 and EX30 testing."),
        CreditEntry("Soolek", "KS-16X testing."),
        CreditEntry("Jonathan Wiesner", "LeaperKim Lynx S testing."),
        CreditEntry("Felix K", "LeaperKim Oryx testing."),
        CreditEntry("WheelLog community", "Open-source (GPLv3) EUC protocol research."),
        CreditEntry("Ilya Shkolnik", "Advice and help, and maintains DarknessBot."),
        CreditEntry("InMotion", "For making my awesome V14."),
    )

    val RESOURCES = listOf(
        CreditEntry(
            "WheelLog community: wheel protocols",
            "Wheellog/wheellog.android, GPLv3. Public reverse-engineering of the EUC BLE protocols, used as " +
                "the reference for the KingSong, Begode, Veteran, Ninebot and InMotion adapters. The " +
                "implementation here is original; no WheelLog code is reused.",
        ),
        CreditEntry(
            "BigSoundBank: engine samples",
            "Joseph SARDIN. CC0 / public domain. Sampled engines in the motor-sound generator.",
        ),
        CreditEntry("Compose Multiplatform, Material 3", "JetBrains / Google. Apache 2.0. Shared UI toolkit + design system."),
        CreditEntry("Ktor, kotlinx.serialization, coroutines", "JetBrains. Apache 2.0. Networking, JSON, structured concurrency."),
        CreditEntry("Kotlin Multiplatform", "JetBrains. Apache 2.0. One shared Android + iOS codebase."),
    )

    const val LICENSE = """MIT License

Copyright (c) 2026 Erwin Ried

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""
}
