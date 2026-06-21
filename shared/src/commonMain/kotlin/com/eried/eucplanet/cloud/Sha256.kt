package com.eried.eucplanet.cloud

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4) → lowercase hex. Used for the EucStats meta
 * `file_sha256`. CommonCrypto-free so it runs identically on Android + iOS K/N
 * (the server checks this against the uploaded CSV, so it must be exact).
 */
fun sha256Hex(message: ByteArray): String {
    val h = intArrayOf(
        0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
        0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    val k = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    val ml = message.size.toLong() * 8
    val withOne = message + byteArrayOf(0x80.toByte())
    val padLen = ((56 - withOne.size % 64) + 64) % 64
    val padded = withOne + ByteArray(padLen) + ByteArray(8) { ((ml ushr (56 - it * 8)) and 0xff).toByte() }

    val w = IntArray(64)
    var i = 0
    while (i < padded.size) {
        for (t in 0 until 16) {
            w[t] = ((padded[i + t * 4].toInt() and 0xff) shl 24) or
                ((padded[i + t * 4 + 1].toInt() and 0xff) shl 16) or
                ((padded[i + t * 4 + 2].toInt() and 0xff) shl 8) or
                (padded[i + t * 4 + 3].toInt() and 0xff)
        }
        for (t in 16 until 64) {
            val s0 = (w[t - 15] rotr 7) xor (w[t - 15] rotr 18) xor (w[t - 15] ushr 3)
            val s1 = (w[t - 2] rotr 17) xor (w[t - 2] rotr 19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (t in 0 until 64) {
            val bigS1 = (e rotr 6) xor (e rotr 11) xor (e rotr 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + bigS1 + ch + k[t] + w[t]
            val bigS0 = (a rotr 2) xor (a rotr 13) xor (a rotr 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = bigS0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        i += 64
    }
    return buildString { for (v in h) append(v.toUInt().toString(16).padStart(8, '0')) }
}

private infix fun Int.rotr(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
