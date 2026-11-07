package com.eried.eucplanet.platform

actual fun gzip(bytes: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.toByteArray()
}
