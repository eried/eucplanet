/**
 * A lookup value with optional aliases
 * <p>
 * toString just gives the primary value,
 * equals just compares against primary value
 * matches() also compares aliases
 *
 * @author ab
 */
package btools.expressions

import kotlin.jvm.JvmField

// Java package-private; public here because BExpressionContext.addLookupValue
// (public) returns it, which Kotlin does not allow for an internal type.
class BExpressionLookupValue(value: String) {
  @JvmField
  internal var value: String = value

  @JvmField
  internal var aliases: MutableList<String>? = null

  override fun toString(): String {
    return value
  }

  fun addAlias(alias: String) {
    if (aliases == null) aliases = ArrayList()
    aliases!!.add(alias)
  }

  override fun equals(other: Any?): Boolean {
    if (other is String) {
      val v: String = other
      return value == v
    }
    if (other is BExpressionLookupValue) {
      val v: BExpressionLookupValue = other

      return value == v.value
    }
    return false
  }

  // Java leaves hashCode as identity; kept so.
  override fun hashCode(): Int = super.hashCode()

  fun matches(s: String): Boolean {
    if (value == s) return true
    if (aliases != null) {
      for (alias in aliases!!) {
        if (alias == s) return true
      }
    }
    return false
  }

}
