package com.eried.eucplanet.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service-mode log buffer. Off by default; the user opts in via the seven-tap
 * gesture on the About-dialog app logo. Once active, every BLE byte in/out
 * and every NOTE marker is appended to an in-memory ring buffer that the
 * Wheel Diagnostics screen renders live and shares as a .txt attachment.
 *
 * Persistence model (matches the user's spec):
 *  - Off when the app starts. The UI flag has to be turned on explicitly.
 *  - Stays on across closes of the diagnostics dialog so the user can
 *    interact with normal app controls and watch them in the log.
 *  - Cleared when the app process exits (singleton lives only in memory).
 *
 * The buffer is bounded so a long session can't OOM the app, drops the
 * oldest entries past [MAX_ENTRIES]. A typical session generates a few
 * hundred lines, so the cap is a safety net rather than a normal case.
 */
object DiagnosticsLogger {

    // Bumped from 9999 so a long-running session (HUD link + wheel BLE +
    // notes) can fill the buffer without dropping the early frames a tester
    // asked about. At ~150 bytes/entry this caps the buffer near ~15 MB,
    // still well under heap pressure on any modern phone.
    private const val MAX_ENTRIES = 99999

    enum class Kind { RECV, SEND, NOTE, TEST, USER, INFO }

    data class Entry(
        val timestampMs: Long,
        val kind: Kind,
        val text: String
    )

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Tracks whether the verbose session-info dump has already been written
     *  for the current enable cycle. Prevents reopening the dialog from
     *  duplicating the phone / Wear / wheel info every time. */
    @Volatile private var sessionInfoCaptured = false

    fun enable() {
        if (_enabled.value) return
        _enabled.value = true
        sessionInfoCaptured = false
        info("entered service mode")
    }

    fun disable() {
        _enabled.value = false
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** Called by the dialog's session-info hook before dumping the phone /
     *  Wear / wheel snapshot. Returns true exactly once per enable cycle. */
    fun shouldCaptureSessionInfo(): Boolean {
        if (sessionInfoCaptured) return false
        sessionInfoCaptured = true
        return true
    }

    fun rx(bytes: ByteArray) = append(Kind.RECV, "${bytes.size}  ${hex(bytes)}")
    fun tx(bytes: ByteArray) = append(Kind.SEND, "${bytes.size}  ${hex(bytes)}")
    fun note(msg: String) = append(Kind.NOTE, msg)
    fun info(msg: String) = append(Kind.INFO, msg)
    fun comment(msg: String) = append(Kind.USER, msg)

    /** Diagnostic test command run from the dialog. Different from a normal SEND. */
    fun cmd(label: String, bytes: ByteArray) =
        append(Kind.TEST, "$label  ${hex(bytes)}")

    private fun append(kind: Kind, text: String) {
        if (!_enabled.value) return
        val list = _entries.value
        val next = if (list.size >= MAX_ENTRIES) {
            list.drop(list.size - MAX_ENTRIES + 1) + Entry(System.currentTimeMillis(), kind, text)
        } else {
            list + Entry(System.currentTimeMillis(), kind, text)
        }
        _entries.value = next
    }

    /** Render the buffer as a shareable text dump. */
    fun render(): String {
        val sb = StringBuilder()
        sb.append("EUC Planet diagnostics log\n")
        sb.append("rendered: ${SESSION_FMT.format(Date())}\n")
        sb.append("entries: ${_entries.value.size}\n\n")
        for (e in _entries.value) {
            sb.append(LINE_FMT.format(Date(e.timestampMs)))
            sb.append(' ')
            sb.append(e.kind.name.padEnd(4))
            sb.append(' ')
            sb.append(e.text)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02x".format(it) }

    private val LINE_FMT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val SESSION_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}
