package com.eried.eucplanet.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual fun readTestFile(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val out = ByteArray(data.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun listTestDir(path: String): List<String> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.map { it as String } ?: emptyList()

actual val goldenOnIos: Boolean = true
