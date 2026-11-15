package btools.util

import kotlin.jvm.JvmField

abstract class LruMapNode {
  @JvmField
  internal var nextInBin: LruMapNode? = null // next entry for hash-bin

  @JvmField
  internal var next: LruMapNode? = null // next in lru sequence (towards mru)

  @JvmField
  internal var previous: LruMapNode? = null // previous in lru sequence (towards lru)

  @JvmField
  var hash: Int = 0
}
