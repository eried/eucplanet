package com.eried.eucplanet.data.repository

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Dropbox pages /files/list_folder and picks the page size itself. Reading the
 * first page and stopping cost one rider every trip past it, and worse: the
 * sync reads a local file whose remote twin is past the last visible page as
 * local-only, so it re-uploaded the same 22 trips on every single sync, forever.
 */
class DropboxListPagingTest {

    private fun file(name: String, size: Long = 100L) =
        name to DropboxRepository.RemoteFile(0L, size)

    private fun page(files: List<Pair<String, DropboxRepository.RemoteFile>>, cursor: String?, more: Boolean) =
        DropboxRepository.ListPage(files.toMap(), cursor, more)

    @Test
    fun `a single page is returned whole`() = runBlocking {
        val out = DropboxRepository.collectPages { cursor ->
            assertNull("no cursor on the first call", cursor)
            page(listOf(file("a.csv"), file("b.csv")), cursor = null, more = false)
        }
        assertEquals(setOf("a.csv", "b.csv"), out?.keys)
    }

    @Test
    fun `every page is walked and the cursor is threaded through`() = runBlocking {
        val seen = mutableListOf<String?>()
        val out = DropboxRepository.collectPages { cursor ->
            seen += cursor
            when (cursor) {
                null -> page(listOf(file("1.csv")), cursor = "c1", more = true)
                "c1" -> page(listOf(file("2.csv")), cursor = "c2", more = true)
                "c2" -> page(listOf(file("3.csv")), cursor = null, more = false)
                else -> error("unexpected cursor $cursor")
            }
        }
        assertEquals(listOf(null, "c1", "c2"), seen)
        assertEquals(setOf("1.csv", "2.csv", "3.csv"), out?.keys)
    }

    @Test
    fun `2000 trips spread over pages all arrive`() = runBlocking {
        // The shape of the tester's folder: far more than one page holds.
        val pageSize = 500
        val out = DropboxRepository.collectPages { cursor ->
            val start = cursor?.toInt() ?: 0
            val names = (start until minOf(start + pageSize, 2000)).map { file("trip_$it.csv") }
            val next = start + pageSize
            page(names, cursor = next.toString(), more = next < 2000)
        }
        assertEquals(2000, out?.size)
    }

    @Test
    fun `a failed page returns null rather than a short folder`() = runBlocking {
        // Half a listing looks exactly like a smaller folder to the sync, which
        // would then re-upload everything missing from it. Refuse instead.
        val out = DropboxRepository.collectPages { cursor ->
            if (cursor == null) page(listOf(file("1.csv")), cursor = "c1", more = true) else null
        }
        assertNull(out)
    }

    @Test
    fun `more pages promised with no cursor is a failure, not a short folder`() = runBlocking {
        val out = DropboxRepository.collectPages { _ ->
            page(listOf(file("1.csv")), cursor = null, more = true)
        }
        assertNull(out)
    }

    @Test
    fun `a cursor that never ends stops instead of looping forever`() = runBlocking {
        var calls = 0
        val out = DropboxRepository.collectPages(maxPages = 5) { _ ->
            calls++
            page(listOf(file("x$calls.csv")), cursor = "always", more = true)
        }
        assertEquals(5, calls)
        assertNull(out)
    }

    @Test
    fun `a page parses entries, cursor and has_more, skipping folders`() {
        val json = JSONObject(
            """
            {"entries":[
              {".tag":"file","name":"trip_a.csv","server_modified":"2026-08-18T12:00:00Z","size":1234},
              {".tag":"folder","name":"subdir"},
              {".tag":"file","name":"trip_b.csv","server_modified":"not a date","size":7}
            ],"cursor":"CURSOR","has_more":true}
            """.trimIndent()
        )
        val page = DropboxRepository.parseListPage(json)
        assertEquals(setOf("trip_a.csv", "trip_b.csv"), page.files.keys)
        assertEquals(1234L, page.files["trip_a.csv"]?.size)
        assertEquals(1787054400L, page.files["trip_a.csv"]?.serverModified)
        // An unparseable date must not drop the file: size is what conflict
        // detection actually uses.
        assertEquals(0L, page.files["trip_b.csv"]?.serverModified)
        assertEquals("CURSOR", page.cursor)
        assertEquals(true, page.hasMore)
    }
}
