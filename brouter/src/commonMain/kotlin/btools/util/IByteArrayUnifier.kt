package btools.util

interface IByteArrayUnifier {
  fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray
}
