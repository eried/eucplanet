package com.eried.eucplanet.platform

actual fun sha256(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(data)

actual fun md5(data: ByteArray): ByteArray = java.security.MessageDigest.getInstance("MD5").digest(data)

private val secureRandom by lazy { java.security.SecureRandom() }

actual fun secureRandomInt(bound: Int): Int = secureRandom.nextInt(bound)
