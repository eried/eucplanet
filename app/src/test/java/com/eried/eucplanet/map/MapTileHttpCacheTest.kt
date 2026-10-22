package com.eried.eucplanet.map

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTileHttpCacheTest {
    @Test
    fun resizeEvictsEntryThatExceedsNewBudget() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("map-http-cache").toFile()
        val cache = MapTileHttpCache(directory, 32_768L)
        try {
            server.start()
            val url = server.url("/tile").toString()
            val first = ByteArray(8_192) { 1 }
            val second = ByteArray(8_192) { 2 }
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(first.toString(Charsets.ISO_8859_1)))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(second.toString(Charsets.ISO_8859_1)))

            assertArrayEquals(first, cache.fetch(url) { it.readBytes() })
            assertArrayEquals(first, cache.fetch(url) { it.readBytes() })
            assertTrue(server.requestCount == 1)

            cache.resize(4_096L)
            assertArrayEquals(second, cache.fetch(url) { it.readBytes() })
            assertTrue(server.requestCount == 2)
        } finally {
            cache.close()
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resizeWaitsForActiveReaderAndReusesDirectorySafely() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("map-http-cache-reader").toFile()
        val cache = MapTileHttpCache(directory, 32_768L)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            server.start()
            val url = server.url("/tile").toString()
            val body = ByteArray(8_192) { 7 }
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(body.toString(Charsets.ISO_8859_1)))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(body.toString(Charsets.ISO_8859_1)))

            val fetch = executor.submit<ByteArray?> {
                cache.fetch(url) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                    it.readBytes()
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val resize = executor.submit { cache.resize(4_096L) }
            assertFalse(resize.isDone)
            release.countDown()
            assertArrayEquals(body, fetch.get(2, TimeUnit.SECONDS))
            resize.get(2, TimeUnit.SECONDS)
            assertArrayEquals(body, cache.fetch(url) { it.readBytes() })
        } finally {
            release.countDown()
            cache.close()
            executor.shutdownNow()
            server.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun failuresReturnNullAndLeaveOwnerUsable() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("map-http-cache-failure").toFile()
        val cache = MapTileHttpCache(directory, 32_768L)
        try {
            server.start()
            val url = server.url("/tile").toString()
            val body = ByteArray(64) { 3 }
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(body.toString(Charsets.ISO_8859_1)))
            server.enqueue(MockResponse().setHeader("Cache-Control", "max-age=3600").setBody(body.toString(Charsets.ISO_8859_1)))

            assertNull(cache.fetch(url) { it.readBytes() })
            assertNull(cache.fetch(url) { error("decoder failure") })
            cache.resize(16_384L)
            assertArrayEquals(body, cache.fetch(url) { it.readBytes() })
        } finally {
            cache.close()
            server.shutdown()
            directory.deleteRecursively()
        }
    }
}
