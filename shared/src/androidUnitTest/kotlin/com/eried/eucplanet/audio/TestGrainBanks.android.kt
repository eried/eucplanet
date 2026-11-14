package com.eried.eucplanet.audio

import java.io.File

actual fun readTestFile(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

actual fun listTestDir(path: String): List<String> = File(path).list()?.toList() ?: emptyList()

actual val goldenOnIos: Boolean = false
