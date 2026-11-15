package btools.codec

import btools.util.BitCoderContext
import kotlin.jvm.JvmField

/**
 * Encoder/Decoder for way-/node-descriptions
 * <p>
 * It detects identical descriptions and sorts them
 * into a huffman-tree according to their frequencies
 * <p>
 * Adapted for 3-pass encoding (counters -&gt; statistics -&gt; encoding )
 * but doesn't do anything at pass1
 */
class TagValueCoder(bc: BitCoderContext, buffers: DataBuffers, validator: TagValueValidator?) {
  private var tree: Any?
  private var bc: BitCoderContext

  init {
    tree = decodeTree(bc, buffers, validator)
    this.bc = bc
  }

  fun decodeTagValueSet(): TagValueWrapper? {
    var node = tree
    while (node is TreeNode) {
      val tn = node
      val nextBit = bc.decodeBit()
      node = if (nextBit) tn.child2 else tn.child1
    }
    return node as TagValueWrapper?
  }

  private fun decodeTree(bc: BitCoderContext, buffers: DataBuffers, validator: TagValueValidator?): Any? {
    val isNode = bc.decodeBit()
    if (isNode) {
      val node = TreeNode()
      node.child1 = decodeTree(bc, buffers, validator)
      node.child2 = decodeTree(bc, buffers, validator)
      return node
    }

    val buffer = buffers.tagbuf1
    val ctx = buffers.bctx1
    ctx.reset(buffer)

    var inum = 0
    var lastEncodedInum = 0

    var hasdata = false
    while (true) {
      val delta = bc.decodeVarBits()
      if (!hasdata) {
        if (delta == 0) {
          return null
        }
      }
      if (delta == 0) {
        ctx.encodeVarBits(0)
        break
      }
      inum += delta

      val data = bc.decodeVarBits()

      if (validator == null || validator.isLookupIdxUsed(inum)) {
        hasdata = true
        ctx.encodeVarBits(inum - lastEncodedInum)
        ctx.encodeVarBits(data)
        lastEncodedInum = inum
      }
    }

    val res: ByteArray
    val len = ctx.closeAndGetEncodedLength()
    if (validator == null) {
      res = ByteArray(len)
      buffer.copyInto(res, 0, 0, len)
    } else {
      res = validator.unify(buffer, 0, len)
    }

    val accessType = if (validator == null) 2 else validator.accessType(res)
    if (accessType > 0) {
      val w = TagValueWrapper()
      w.data = res
      w.accessType = accessType
      return w
    }
    return null
  }

  class TreeNode {
    @JvmField
    var child1: Any? = null

    @JvmField
    var child2: Any? = null
  }
}
