package com.eried.eucplanet.platform

actual fun gzip(bytes: ByteArray): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    java.util.zip.GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.toByteArray()
}

actual fun inflateRaw(bytes: ByteArray): ByteArray {
    val inflater = java.util.zip.Inflater(true)
    try {
        inflater.setInput(bytes)
        val out = java.io.ByteArrayOutputStream(bytes.size * 4)
        val buf = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                throw java.util.zip.DataFormatException("truncated deflate data")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}
