package com.eried.eucplanet.platform

actual fun String(bytes: ByteArray, charset: Charset): String = String(bytes, 0, bytes.size, charset)

actual fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String = when (charset) {
    Charset.UTF_8 -> bytes.decodeToString(offset, offset + length)
    Charset.US_ASCII -> CharsetCodec.decodeAscii(bytes, offset, length)
    Charset.ISO_8859_1 -> CharsetCodec.decodeLatin1(bytes, offset, length)
}

actual fun ByteArray.toString(charset: Charset): String = String(this, charset)

actual fun String.toByteArray(charset: Charset): ByteArray = when (charset) {
    Charset.UTF_8 -> encodeToByteArray()
    Charset.US_ASCII -> CharsetCodec.encodeSingleByte(this, 0x7F)
    Charset.ISO_8859_1 -> CharsetCodec.encodeSingleByte(this, 0xFF)
}
