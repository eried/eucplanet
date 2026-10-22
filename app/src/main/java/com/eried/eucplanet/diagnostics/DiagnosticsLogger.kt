package com.eried.eucplanet.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    /** Floor between two snapshots handed to collectors. A snapshot is an
     *  O(n) copy of the buffer, so at BLE rate one per append was the cost. */
    private const val PUBLISH_MIN_MS = 100L

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

    // The buffer proper. An ArrayDeque, so an append is O(1) rather than the
    // whole-list copy it used to be (up to 99999 entries, several times a
    // second once raw BLE is flowing). Guarded by [lock], as is every field
    // down to [trailingPublish].
    private val lock = Any()
    private val buffer = ArrayDeque<Entry>()
    // Last immutable snapshot handed out, and whether the buffer has moved
    // on since it was taken.
    private var cached: List<Entry> = emptyList()
    private var cacheStale = false
    private var lastPublishMs = 0L
    private var trailingPublish: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /**
     * Live view for the diagnostics screen.
     *
     * Collectors see a fresh snapshot at most every [PUBLISH_MIN_MS] while
     * entries arrive (a trailing publish makes sure the last line lands) and
     * nothing at all while nobody collects, which is the common case: service
     * mode on, screen closed. Reading [StateFlow.value] directly is always
     * current, so [render] and the tests see every entry the instant it is
     * appended.
     */
    val entries: StateFlow<List<Entry>> = object : StateFlow<List<Entry>> by _entries {
        override val value: List<Entry> get() = snapshot()
    }

    /**
     * Current contents as an immutable list, reusing the last copy when
     * nothing changed. With no collector the copy is also published, so a
     * screen that opens later starts from it and not from a stale one. With
     * a collector the throttle in [append] owns publishing: doing it here
     * would recompose, which reads [value], which would publish again.
     */
    private fun snapshot(): List<Entry> = synchronized(lock) {
        if (cacheStale) {
            cached = buffer.toList()
            cacheStale = false
        }
        if (_entries.subscriptionCount.value == 0 && _entries.value !== cached) {
            _entries.value = cached
            lastPublishMs = System.currentTimeMillis()
        }
        cached
    }

    /** Hand the current buffer to collectors. Call with [lock] held. */
    private fun publishLocked(nowMs: Long) {
        if (cacheStale) {
            cached = buffer.toList()
            cacheStale = false
        }
        if (_entries.value !== cached) _entries.value = cached
        lastPublishMs = nowMs
    }

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
        synchronized(lock) {
            buffer.addAll(replayed)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            cacheStale = true
            publishLocked(System.currentTimeMillis())
        }
    }

    fun disable() {
        _enabled.value = false
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            cacheStale = true
            publishLocked(System.currentTimeMillis())
        }
    }

    /** Called by the dialog's session-info hook before dumping the phone /
     *  Wear / wheel snapshot. Returns true exactly once per enable cycle. */
    fun shouldCaptureSessionInfo(): Boolean {
        if (sessionInfoCaptured) return false
        sessionInfoCaptured = true
        return true
    }

    // Raw BLE traffic. The hex formatting is the expensive part, and it used
    // to run for every packet even with service mode off, so the check comes
    // first. note / info / comment hand over a ready string and stay as is.
    fun rx(bytes: ByteArray) {
        if (!_enabled.value) return
        append(Kind.RECV, "${bytes.size}  ${hex(bytes)}")
    }
    fun tx(bytes: ByteArray) {
        if (!_enabled.value) return
        append(Kind.SEND, "${bytes.size}  ${hex(bytes)}")
    }
    fun note(msg: String) = append(Kind.NOTE, msg)
    fun info(msg: String) = append(Kind.INFO, msg)
    fun comment(msg: String) = append(Kind.USER, msg)

    /** Diagnostic test command run from the dialog. Different from a normal SEND. */
    fun cmd(label: String, bytes: ByteArray) {
        if (!_enabled.value) return
        append(Kind.TEST, "$label  ${hex(bytes)}")
    }

    private fun append(kind: Kind, text: String) {
        if (!_enabled.value) return
        val entry = Entry(System.currentTimeMillis(), kind, text)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            cacheStale = true
            // Nobody collecting: the copy waits for whoever asks next.
            if (_entries.subscriptionCount.value == 0) return
            val due = lastPublishMs + PUBLISH_MIN_MS
            if (entry.timestampMs >= due) {
                publishLocked(entry.timestampMs)
            } else if (trailingPublish == null) {
                trailingPublish = scope.launch {
                    delay(due - entry.timestampMs)
                    synchronized(lock) {
                        trailingPublish = null
                        publishLocked(System.currentTimeMillis())
                    }
                }
            }
        }
    }

    /** Render the buffer as a shareable text dump. */
    fun render(): String {
        val all = snapshot()
        val sb = StringBuilder()
        sb.append("EUC Planet diagnostics log\n")
        sb.append("rendered: ${SESSION_FMT.format(Date())}\n")
        sb.append("entries: ${all.size}\n\n")
        for (e in all) {
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
