package com.eried.eucplanet.app

import com.eried.eucplanet.platform.toNSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** The studio's kept copies, which the View action opens in the app. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosSavedMediaTest {
    private val dir = NSTemporaryDirectory() + "saved_" + Uuid.random()

    @AfterTest fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    @Test fun aFileNameIsFileSafeAndKeepsTheExtension() {
        assertEquals(
            "001763164028000_6F1B-22_L0_001.png",
            IosSavedMedia.fileName("6F1B-22/L0/001", "EUC_20261115_010708.PNG", 1_763_164_028_000L),
        )
        assertEquals("000000000000005_a.bin", IosSavedMedia.fileName("a", "noext", 5L))
    }

    @Test fun aKeptPhotoIsFoundByItsIdentifier() {
        val media = IosSavedMedia(dir)
        media.keepBytes("A/L0/001", byteArrayOf(1, 2, 3), "x.jpg", nowMs = 10L)
        val path = assertNotNull(media.pathFor("A/L0/001"))
        assertTrue(path.endsWith(".jpg"))
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
        assertNull(media.pathFor("B/L0/001"))
    }

    @Test fun aKeptFileIsMovedNotCopied() {
        val media = IosSavedMedia(dir)
        val src = NSTemporaryDirectory() + "src_" + Uuid.random() + ".mp4"
        assertTrue(byteArrayOf(9).toNSData().writeToFile(src, true))
        media.keepFile("V/L0/001", src, "clip.mp4", nowMs = 10L)
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath(src))
        assertNotNull(media.pathFor("V/L0/001"))
    }

    @Test fun onlyTheNewestCopiesAreKept() {
        val media = IosSavedMedia(dir, keep = 2)
        media.keepBytes("one", byteArrayOf(1), "a.png", nowMs = 1L)
        media.keepBytes("two", byteArrayOf(2), "b.png", nowMs = 2L)
        media.keepBytes("three", byteArrayOf(3), "c.gif", nowMs = 3L)
        assertNull(media.pathFor("one"))
        assertNotNull(media.pathFor("two"))
        assertNotNull(media.pathFor("three"))
    }
}
