package btools.expressions

import kotlin.jvm.JvmField

import btools.util.LruMapNode

class CacheNode : LruMapNode() {
  @JvmField
  internal var ab: ByteArray? = null

  @JvmField
  internal var vars: FloatArray? = null

  override fun hashCode(): Int {
    return hash
  }

  override fun equals(other: Any?): Boolean {
    val n = other as CacheNode
    if (hash != n.hash) {
      return false
    }
    if (ab == null) {
      return true // hack: null = crc match only
    }
    return ab.contentEquals(n.ab)
  }
}
