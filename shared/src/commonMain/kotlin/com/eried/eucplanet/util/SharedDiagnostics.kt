package com.eried.eucplanet.util

/**
 * Shared diagnostics note sink. Shared (commonMain) parsers emit Service-Mode
 * notes through this; the Android app installs a [handler] that forwards to the
 * full `DiagnosticsLogger`. Default no-op — on iOS, or before the app wires it,
 * notes are simply dropped (Service Mode is Android-only).
 */
object SharedDiagnostics {
    var handler: ((String) -> Unit)? = null
    fun note(msg: String) { handler?.invoke(msg) }
}
