package com.eried.eucplanet.platform

/**
 * `java.util.Arrays.binarySearch(double[], double)`, step for step: the index
 * of [key], or `-(insertion point) - 1` when it is not there. Equal values
 * compare as the JDK does (by their bits), so a run of duplicates lands on
 * the same one.
 */
fun DoubleArray.jdkBinarySearch(key: Double): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val midVal = this[mid]
        if (midVal < key) {
            low = mid + 1
        } else if (midVal > key) {
            high = mid - 1
        } else {
            val midBits = midVal.toBits()
            val keyBits = key.toBits()
            if (midBits == keyBits) return mid
            else if (midBits < keyBits) low = mid + 1
            else high = mid - 1
        }
    }
    return -(low + 1)
}

/**
 * `java.util.PriorityQueue` for shared code: the same binary heap with the
 * same sift steps, so items that compare equal come out in the same order as
 * they did on the JVM.
 */
class PriorityQueue<E>(private val comparator: Comparator<in E>) {
    private val heap = ArrayList<E>()

    val size: Int get() = heap.size
    fun isEmpty(): Boolean = heap.isEmpty()

    fun add(e: E): Boolean {
        heap.add(e)
        siftUp(heap.size - 1, e)
        return true
    }

    operator fun plusAssign(e: E) { add(e) }

    fun peek(): E? = heap.firstOrNull()

    fun poll(): E? {
        if (heap.isEmpty()) return null
        val result = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) siftDown(0, last)
        return result
    }

    private fun siftUp(start: Int, x: E) {
        var k = start
        while (k > 0) {
            val parent = (k - 1) ushr 1
            val e = heap[parent]
            if (comparator.compare(x, e) >= 0) break
            heap[k] = e
            k = parent
        }
        heap[k] = x
    }

    private fun siftDown(start: Int, x: E) {
        var k = start
        val n = heap.size
        val half = n ushr 1
        while (k < half) {
            var child = (k shl 1) + 1
            var c = heap[child]
            val right = child + 1
            if (right < n && comparator.compare(c, heap[right]) > 0) {
                child = right
                c = heap[child]
            }
            if (comparator.compare(x, c) <= 0) break
            heap[k] = c
            k = child
        }
        heap[k] = x
    }
}

/**
 * An access-ordered `LinkedHashMap` with `removeEldestEntry` returning
 * `size > maxSize`: a read moves the entry to the newest end, and an insert
 * past [maxSize] drops the least recently used one. Not thread safe, like
 * the map it stands in for.
 */
class LruMap<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = map.size

    operator fun get(key: K): V? {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val v = map.remove(key) as V
        map[key] = v
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        if (map.size > maxSize) map.remove(map.keys.first())
    }

    fun getOrPut(key: K, make: () -> V): V {
        get(key)?.let { return it }
        val v = make()
        set(key, v)
        return v
    }

    fun clear() = map.clear()
}
