// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import kotlin.jvm.JvmField

import btools.util.BFiles


class BExpressionMetaData {

  @JvmField
  var lookupVersion: Short = -1

  @JvmField
  var lookupMinorVersion: Short = -1

  @JvmField
  var minAppVersion: Short = -1

  private val listeners: MutableMap<String, BExpressionContext> = HashMap()

  fun registerListener(context: String, ctx: BExpressionContext) {
    listeners[context] = ctx
  }

  fun readMetaData(lookupsFile: String) {
    try {
      // the lines java.io.BufferedReader.readLine returned
      val lines = jReadLines(BFiles.readBytes(lookupsFile).decodeToString())

      var ctx: BExpressionContext? = null

      for (rawLine in lines) {
        val line = jTrim(rawLine)
        if (line.length == 0 || line.startsWith("#")) continue
        if (line.startsWith(CONTEXT_TAG)) {
          ctx = listeners[line.substring(CONTEXT_TAG.length)]
          continue
        }
        if (line.startsWith(VERSION_TAG)) {
          lookupVersion = line.substring(VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(MINOR_VERSION_TAG)) {
          lookupMinorVersion = line.substring(MINOR_VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(MIN_APP_VERSION_TAG)) {
          minAppVersion = line.substring(MIN_APP_VERSION_TAG.length).toShort()
          continue
        }
        if (line.startsWith(VARLENGTH_TAG)) { // tag removed...
          continue
        }
        if (ctx != null) ctx.parseMetaLine(line)
      }

      for (c in listeners.values) {
        c.finishMetaParsing()
      }

    } catch (e: Exception) {
      throw RuntimeException(e)
    }
  }

  companion object {
    private const val CONTEXT_TAG = "---context:"
    private const val VERSION_TAG = "---lookupversion:"
    private const val MINOR_VERSION_TAG = "---minorversion:"
    private const val VARLENGTH_TAG = "---readvarlength"
    private const val MIN_APP_VERSION_TAG = "---minappversion:"
  }
}
