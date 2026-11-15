package btools.expressions

import kotlin.jvm.JvmField

import btools.util.LruMapNode

class VarWrapper : LruMapNode() {
  @JvmField
  internal var vars: FloatArray? = null

  override fun hashCode(): Int {
    return hash
  }

  override fun equals(other: Any?): Boolean {
    val n = other as VarWrapper
    if (hash != n.hash) {
      return false
    }
    return vars.contentEquals(n.vars)
  }
}
