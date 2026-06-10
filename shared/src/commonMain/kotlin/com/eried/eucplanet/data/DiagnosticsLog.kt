package com.eried.eucplanet.data

import com.eried.eucplanet.util.SharedDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ring buffer of Service-Mode inspect notes. The wheel adapters emit raw-frame
 * dumps through [SharedDiagnostics.note]; on Android `:app` routes those to the
 * full DiagnosticsLogger, and here we also capture them so the shared Service
 * Mode "Inspect" view can show live telemetry frames on iOS too.
 */
object DiagnosticsLog {
    private const val MAX = 300

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Tee SharedDiagnostics notes into this buffer. Idempotent. */
    fun install() {
        SharedDiagnostics.handler = { line ->
            val next = _lines.value + line
            _lines.value = if (next.size > MAX) next.subList(next.size - MAX, next.size) else next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
