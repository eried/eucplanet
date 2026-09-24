package com.eried.eucplanet.map

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request

internal class MapTileHttpCache(
    private val directory: File,
    maxBytes: Long,
) : Closeable {
    private companion object {
        const val TIMEOUT_SECONDS = 8L
        const val USER_AGENT = "EUCPlanet-Maps/1.0 (github.com/eried/eucplanet)"
    }

    private val lifecycle = ReentrantReadWriteLock(true)
    private val clientLock = Any()
    private var maxBytes = maxBytes.also { require(it > 0L) }
    private var cache: Cache? = null
    private var client: OkHttpClient? = null
    private var closed = false

    fun <T> fetch(url: String, decode: (InputStream) -> T?): T? {
        lifecycle.readLock().lock()
        try {
            if (closed) return null
            val current = synchronized(clientLock) {
                if (closed) return null
                client ?: openClient()
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            return current.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()?.use(decode)
            }
        } catch (_: Exception) {
            return null
        } finally {
            lifecycle.readLock().unlock()
        }
    }

    fun resize(maxBytes: Long) {
        require(maxBytes > 0L)
        lifecycle.writeLock().lock()
        try {
            check(!closed) { "Map tile HTTP cache is closed" }
            if (this.maxBytes == maxBytes) return
            synchronized(clientLock) {
                check(!closed) { "Map tile HTTP cache is closed" }
                val oldCache = cache
                val oldClient = client
                if (oldCache == null || oldClient == null) {
                    this.maxBytes = maxBytes
                    return
                }
                oldCache.close()
                var replacement: Cache? = null
                try {
                    replacement = Cache(directory, maxBytes)
                    replacement.initialize()
                    replacement.flush()
                    val newClient = oldClient.newBuilder().cache(replacement).build()
                    this.maxBytes = maxBytes
                    cache = replacement
                    client = newClient
                } catch (error: Exception) {
                    runCatching { replacement?.close() }
                    client = null
                    cache = null
                    this.maxBytes = maxBytes
                    throw error
                }
            }
        } finally {
            lifecycle.writeLock().unlock()
        }
    }

    override fun close() {
        lifecycle.writeLock().lock()
        try {
            if (closed) return
            closed = true
            synchronized(clientLock) {
                runCatching { cache?.close() }
                cache = null
                client = null
            }
        } finally {
            lifecycle.writeLock().unlock()
        }
    }

    private fun openClient(): OkHttpClient {
        val newCache = Cache(directory, maxBytes)
        val newClient = OkHttpClient.Builder()
            .cache(newCache)
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        cache = newCache
        client = newClient
        return newClient
    }
}
