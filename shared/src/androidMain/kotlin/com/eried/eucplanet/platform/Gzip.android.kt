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

actual fun gunzip(bytes: ByteArray): ByteArray =
    java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { it.readBytes() }

actual fun deflateRaw(bytes: ByteArray): ByteArray {
    val d = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
    d.setInput(bytes); d.finish()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf))
    d.end()
    return out.toByteArray()
}

actual fun inflateRawCapped(bytes: ByteArray, maxBytes: Int): ByteArray {
    val inf = java.util.zip.Inflater(true)
    inf.setInput(bytes)
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(1024)
    while (!inf.finished()) {
        val n = inf.inflate(buf)
        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
        out.write(buf, 0, n)
        if (out.size() > maxBytes) break
    }
    inf.end()
    return out.toByteArray()
}
