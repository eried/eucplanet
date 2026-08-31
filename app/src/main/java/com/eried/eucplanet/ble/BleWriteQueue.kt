package com.eried.eucplanet.ble

/**
 * What is waiting to go out over the BLE link, and in what order.
 *
 * Everything the app writes used to share one FIFO, which is fine until the
 * link is busy, and on a wheel that answers a poll with eight notification
 * fragments it is busy most of the time. A rider's horn then queues behind a
 * wall of telemetry polls and is dropped by the same rule that drops a poll,
 * even though the two are worth completely different amounts: another poll
 * comes round in 250 ms, and nothing brings back a horn.
 *
 * So the two are separated here.
 *
 * **Commands go first, and they queue.** A tap on the horn, the light, or a
 * settings write is a one-shot the rider is waiting on.
 *
 * **Polls do not queue, they replace.** A poll that has been sitting behind
 * another poll is asking for telemetry that the newer one already asks for, so
 * holding both is pointless and holding a backlog of them is how a queue fills
 * up and starts refusing commands. Only the newest is kept.
 *
 * Free of Android and of coroutines, so the ordering rules can be tested
 * directly rather than through a Bluetooth stack.
 */
class BleWriteQueue(private val commandCapacity: Int = COMMAND_CAPACITY) {

    /** Why a write exists, which is what decides how hard the link tries. */
    enum class Kind {
        /** Telemetry the poll loop asks for on a timer. Cheap to lose. */
        POLL,

        /** Anything the rider or the app asked for once: horn, light, lock, a
         *  settings write, the connect handshake. Expensive to lose. */
        COMMAND,
    }

    class Entry(val kind: Kind, val data: ByteArray)

    companion object {
        /** Commands waiting at once. Deep enough for a burst of taps and a
         *  handshake together; a link healthy enough to matter never fills it. */
        const val COMMAND_CAPACITY = 16

        /**
         * Attempts before the link gives up on a write.
         *
         * A poll barely tries: the next one is along in a poll interval, and
         * retrying a stale request just keeps the stack busy for the write that
         * matters. A command tries hard, because the rider is standing there
         * with a thumb on the horn.
         */
        fun maxAttempts(kind: Kind): Int = if (kind == Kind.COMMAND) 8 else 2

        /**
         * How long to wait before attempt [attempt] + 1, in ms.
         *
         * Escalating, because a flat retry cadence is what made this fail on a
         * V8S: the stack stays busy for up to about 300 ms while it streams a
         * reply, and four tries 80 ms apart give up 60 ms short of that, every
         * time. Backing off further each try covers the tail without hammering
         * a stack that has already said it is busy.
         */
        fun retryDelayMs(attempt: Int): Long = (attempt.coerceAtLeast(1) * 60L).coerceAtMost(420L)
    }

    private val commands = ArrayDeque<ByteArray>()
    private var pendingPoll: ByteArray? = null

    /** Polls thrown away because a newer one arrived. Normal, worth counting. */
    var supersededPolls = 0
        private set

    /** Commands thrown away because the queue was full. Never normal. */
    var droppedCommands = 0
        private set

    val isEmpty: Boolean get() = commands.isEmpty() && pendingPoll == null
    val commandsWaiting: Int get() = commands.size

    /**
     * Offer a write. Returns false only when a command had to be dropped,
     * which means the link has been stuck long enough to fill the queue.
     * A poll is never refused: it replaces whichever poll was waiting.
     */
    fun offer(kind: Kind, data: ByteArray): Boolean {
        if (kind == Kind.POLL) {
            if (pendingPoll != null) supersededPolls++
            pendingPoll = data
            return true
        }
        if (commands.size >= commandCapacity) {
            // Drop the oldest: on a backed-up link the newest tap is the one
            // that still reflects what the rider wants.
            commands.removeFirst()
            droppedCommands++
            commands.addLast(data)
            return false
        }
        commands.addLast(data)
        return true
    }

    /** The next write to hand to the link, commands first. Null when idle. */
    fun take(): Entry? {
        commands.removeFirstOrNull()?.let { return Entry(Kind.COMMAND, it) }
        pendingPoll?.let {
            pendingPoll = null
            return Entry(Kind.POLL, it)
        }
        return null
    }

    fun clear() {
        commands.clear()
        pendingPoll = null
    }
}
