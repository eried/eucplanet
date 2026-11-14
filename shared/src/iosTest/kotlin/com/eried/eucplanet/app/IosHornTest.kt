package com.eried.eucplanet.app

import com.eried.eucplanet.audio.HornImportResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The horn sound import keeps Android's rules (HornPlayer): a short, small, readable clip. */
@OptIn(ExperimentalForeignApi::class)
class IosHornTest {
    private val root = NSTemporaryDirectory() + "horn-test"
    private val dir = "$root/horn"

    @AfterTest fun clean() {
        NSFileManager.defaultManager.removeItemAtPath(root, null)
    }

    /** A mono 8 kHz 16-bit WAV of [seconds] of silence. */
    private fun wav(name: String, seconds: Int): String {
        val rate = 8000
        val data = rate * 2 * seconds
        val b = ByteArray(44 + data)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun le(at: Int, v: Int, n: Int) { for (i in 0 until n) b[at + i] = (v shr (8 * i)).toByte() }
        str(0, "RIFF"); le(4, 36 + data, 4); str(8, "WAVE")
        str(12, "fmt "); le(16, 16, 4); le(20, 1, 2); le(22, 1, 2)
        le(24, rate, 4); le(28, rate * 2, 4); le(32, 2, 2); le(34, 16, 2)
        str(36, "data"); le(40, data, 4)
        return write(name, b)
    }

    private fun write(name: String, bytes: ByteArray): String {
        NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
        val path = "$root/$name"
        bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
            .writeToFile(path, true)
        return path
    }

    @Test fun aShortClipIsKeptAndReady() {
        val horn = IosHorn(dir)
        assertFalse(horn.isReady)
        assertEquals(HornImportResult.OK, horn.import(wav("beep.wav", 1)))
        assertTrue(horn.isReady)
        // The copy is the app's: a new player over the same folder finds it.
        assertTrue(IosHorn(dir).isReady)
    }

    @Test fun overFiveSecondsIsASongNotAHorn() {
        assertEquals(HornImportResult.TOO_LONG, IosHorn(dir).import(wav("song.wav", 6)))
    }

    @Test fun notAudioIsUnreadable() {
        assertEquals(HornImportResult.UNREADABLE, IosHorn(dir).import(write("notes.wav", "not audio".encodeToByteArray())))
    }

    @Test fun overTwoMegabytesIsTooBig() {
        assertEquals(HornImportResult.TOO_BIG, IosHorn(dir).import(write("big.wav", ByteArray(2 * 1024 * 1024 + 1))))
    }

    @Test fun aRejectedClipKeepsTheOneBefore() {
        val horn = IosHorn(dir)
        assertEquals(HornImportResult.OK, horn.import(wav("beep.wav", 1)))
        assertEquals(HornImportResult.TOO_LONG, horn.import(wav("song.wav", 6)))
        assertTrue(horn.isReady)
    }
}
