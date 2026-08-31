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

    /**
     * True while Legal Mode Lockdown is armed. Service mode records raw BLE and
     * has to stop with the other recorders, but this is an object with no
     * injection, so the state is pushed in from EucPlanetApp rather than pulled.
     */
    @Volatile
    var lockedDown: Boolean = false

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Tracks whether the verbose session-info dump has already been written
     *  for the current enable cycle. Prevents reopening the dialog from
     *  duplicating the phone / Wear / wheel info every time. */
    @Volatile private var sessionInfoCaptured = false

    /**
     * Traces that record whether or not anyone is watching, and get replayed
     * into the buffer when service mode opens.
     *
     * Service mode being off is a no-op for [append], which is what keeps raw
     * BLE from costing anything for the riders who never open it. The cost of
     * that is a capture only ever starting when the rider went looking, so an
     * incident they opened the screen to investigate has already lost its own
     * run-up. A trace small enough to always keep can register here and hand
     * that run-up over instead. See `HudLinkTrace`.
     *
     * Each provider is asked for the entries it has recorded since the last
     * time it was asked, oldest first, so reopening service mode cannot write
     * the same line twice.
     */
    private val backfills = mutableListOf<() -> List<Entry>>()

    fun registerBackfill(provider: () -> List<Entry>) {
        synchronized(backfills) { backfills += provider }
    }

    fun enable() {
        if (lockedDown) return
        if (_enabled.value) return
        _enabled.value = true
        sessionInfoCaptured = false
        replayBackfills()
        info("entered service mode")
    }

    /** Fold the always-on traces in before the session marker, so the capture
     *  reads in the order things actually happened rather than starting at the
     *  moment the rider went looking. */
    private fun replayBackfills() {
        val providers = synchronized(backfills) { backfills.toList() }
        if (providers.isEmpty()) return
        val replayed = providers
            .flatMap { runCatching { it() }.getOrDefault(emptyList()) }
            .sortedBy { it.timestampMs }
        if (replayed.isEmpty()) return
        _entries.value = (_entries.value + replayed).takeLast(MAX_ENTRIES)
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
