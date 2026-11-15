package btools.codec

import kotlin.jvm.JvmField

/**
 * TagValueWrapper wrapps a description bitmap
 * to add the access-type
 */
class TagValueWrapper {
  @JvmField
  var data: ByteArray? = null

  @JvmField
  var accessType: Int = 0
}
