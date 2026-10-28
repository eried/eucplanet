package com.eried.eucplanet.platform

/** SHA-256 of [data]: `MessageDigest.getInstance("SHA-256")` on Android, CommonCrypto on iOS. */
expect fun sha256(data: ByteArray): ByteArray

/** A uniform int in `0 until bound` from the platform's secure generator (`SecureRandom.nextInt`). */
expect fun secureRandomInt(bound: Int): Int
