package com.eried.eucplanet.service

/**
 * Appends [item] to a rolling time window and keeps the window bounded.
 *
 * Age alone is not a safe bound: if the wall clock steps backwards (a network
 * time fix, the rider changing the time), `now - oldest` goes negative and
 * nothing is ever trimmed, so the deque grows for the rest of the ride. A Play
 * crash report showed exactly that ending in an OutOfMemoryError. So a clock
 * that runs backwards starts the window over, and [maxCount] caps it whatever
 * the timestamps say.
 */
internal fun <T> ArrayDeque<T>.addToWindow(
    item: T,
    now: Long,
    timeOf: (T) -> Long,
    maxAgeMs: Long,
    maxCount: Int,
) {
    if (isNotEmpty() && now < timeOf(last())) clear()
    addLast(item)
    while (isNotEmpty() && now - timeOf(first()) > maxAgeMs) removeFirst()
    while (size > maxCount) removeFirst()
}
