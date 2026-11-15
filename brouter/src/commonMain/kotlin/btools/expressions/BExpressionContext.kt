// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import kotlin.jvm.JvmField

import btools.util.BFiles
import btools.util.BitCoderContext
import btools.util.Crc32
import btools.util.IByteArrayUnifier
import btools.util.LruMap
import btools.util.fileName
import btools.util.jIsWhitespace
import btools.util.jParseFloat
import btools.util.tokenize


abstract class BExpressionContext : IByteArrayUnifier {

  private var context: String
  private var _inOurContext = false

  // the profile text and read position replace the java.io.BufferedReader
  private var _text: String? = null
  private var _pos = 0
  private var _readerDone = false

  @JvmField
  var _modelClass: String? = null

  private val lookupNumbers: MutableMap<String, Int> = HashMap()
  private val lookupValues: MutableList<Array<BExpressionLookupValue>> = ArrayList()
  private val lookupNames: MutableList<String> = ArrayList()
  private val lookupHistograms: MutableList<IntArray> = ArrayList()
  private var lookupIdxUsed: BooleanArray? = null

  private var lookupDataFrozen = false

  private var lookupData = IntArray(0)

  private val ctxDecode = BitCoderContext(ByteArray(0))

  private val variableNumbers: MutableMap<String, Int> = HashMap()

  @JvmField
  internal var lastAssignedExpression: MutableList<BExpression?>? = ArrayList()

  @JvmField
  internal var skipConstantExpressionOptimizations = false

  @JvmField
  internal var expressionNodeCount = 0

  private var variableData: FloatArray? = null


  // hash-cache for function results
  private val probeCacheNode = CacheNode()
  private var cache: LruMap? = null

  private val probeVarSet = VarWrapper()
  private var resultVarCache: LruMap? = null

  private var expressionList: MutableList<BExpression>? = null

  private var minWriteIdx = 0

  // build-in variable indexes for fast access
  private var buildInVariableIdx: IntArray? = null
  private var nBuildInVars = 0

  private var currentVars: FloatArray? = null
  private var currentVarOffset = 0

  private var foreignContext: BExpressionContext? = null

  @JvmField
  var noStartWays = IntArray(0)

  internal fun setInverseVars() {
    currentVarOffset = nBuildInVars
  }

  internal abstract fun getBuildInVariableNames(): Array<String>

  fun getBuildInVariable(idx: Int): Float {
    return currentVars!![idx + currentVarOffset]
  }

  private var linenr = 0

  @JvmField
  var meta: BExpressionMetaData?
  private var lookupDataValid = false

  internal constructor(context: String, meta: BExpressionMetaData?) : this(context, 4096, meta)

  /**
   * Create an Expression-Context for the given node
   *
   * @param context  global, way or node - context of that instance
   * @param hashSize size of hashmap for result caching
   */
  internal constructor(context: String, hashSize: Int, meta: BExpressionMetaData?) {
    this.context = context
    this.meta = meta

    if (meta != null) meta.registerListener(context, this)

    // (upstream sets hashSize = 1 when the JVM property disableExpressionCache is set; never set here)

    // create the expression cache
    if (hashSize > 0) {
      cache = LruMap(4 * hashSize, hashSize)
      resultVarCache = LruMap(4096, 4096)
    }
  }


  /**
   * decode byte array to internal lookup data
   */
  fun decode(ab: ByteArray) {
    decode(lookupData, false, ab)
    lookupDataValid = true
  }


  /**
   * decode a byte-array into a lookup data array
   */
  fun decode(ld: IntArray, inverseDirection: Boolean, ab: ByteArray) {
    val ctx = ctxDecode
    ctx.reset(ab)

    // start with first bit hardwired ("reversedirection")
    ld[0] = if (inverseDirection) 2 else 0

    // all others are generic
    var inum = 1
    while (true) {
      var delta = ctx.decodeVarBits()
      if (delta == 0) break
      if (inum + delta > ld.size) break // higher minor version is o.k.

      while (delta-- > 1) ld[inum++] = 0

      // see encoder for value rotation
      val dd = ctx.decodeVarBits()
      var d = if (dd == 7) 1 else (if (dd < 7) dd + 2 else dd + 1)
      if (d >= lookupValues[inum].size && d < 1000) d = 1 // map out-of-range to unknown
      ld[inum++] = d
    }
    while (inum < ld.size) ld[inum++] = 0
  }

  fun getKeyValueDescription(inverseDirection: Boolean, ab: ByteArray): String {
    val sb = StringBuilder(200)
    decode(lookupData, inverseDirection, ab)
    for (inum in 0 until lookupValues.size) { // loop over lookup names
      val va = lookupValues[inum]
      val v = lookupData[inum]
      val value: String? = if (v >= 1000) ((v - 1000) / 100f).toString() else va[v].toString()
      if (value != null && value.length > 0) {
        if (sb.length > 0) sb.append(' ')
        sb.append(lookupNames[inum] + "=" + value)
      }
    }
    return sb.toString()
  }

  fun getLookupKey(name: String): Int {
    var res = -1
    try {
      res = lookupNumbers[name]!!
    } catch (e: Exception) {
    }
    return res
  }

  fun getLookupValue(key: Int): Float {
    var res = 0f
    val v = lookupData[key]
    if (v == 0) return Float.NaN
    if (v < 900) {
      try {
        val va = lookupValues[key]
        val sval = va[v].toString()
        res = jParseFloat(sval)
      } catch (e: NumberFormatException) {
        res = 0f
      }
    } else {
      res = (v - 1000) / 100f
    }
    return res
  }

  private var parsedLines = 0
  private var fixTagsWritten = false

  fun parseMetaLine(line: String) {
    parsedLines++
    val tk = tokenize(line, " ")
    var name = tk[0]
    val value = tk[1]
    val idx = name.indexOf(';')
    if (idx >= 0) name = name.substring(0, idx)

    if (!fixTagsWritten) {
      fixTagsWritten = true
      if ("way" == context) addLookupValue("reversedirection", "yes", null)
      else if ("node" == context) addLookupValue("nodeaccessgranted", "yes", null)
    }
    if ("reversedirection" == name) return // this is hardcoded
    if ("nodeaccessgranted" == name) return // this is hardcoded
    val newValue = addLookupValue(name, value, null)

    // add aliases
    var ti = 2
    while (newValue != null && ti < tk.size) newValue.addAlias(tk[ti++])
  }

  fun finishMetaParsing() {
    if (parsedLines == 0 && "global" != context) {
      throw IllegalArgumentException("lookup table does not contain data for context $context (old version?)")
    }

    // post-process metadata:
    lookupDataFrozen = true

    lookupIdxUsed = BooleanArray(lookupValues.size)
  }

  fun evaluate(lookupData2: IntArray) {
    lookupData = lookupData2
    evaluate()
  }

  private fun evaluate() {
    val list = expressionList!!
    val n = list.size
    for (expidx in 0 until n) {
      list[expidx].evaluate(this)
    }
  }

  private var requests: Long = 0
  private var requests2: Long = 0
  private var cachemisses: Long = 0

  fun cacheStats(): String {
    return "requests=$requests requests2=$requests2 cachemisses=$cachemisses"
  }

  private var lastCacheNode = CacheNode()

  // @Override
  final override fun unify(ab: ByteArray, offset: Int, len: Int): ByteArray {
    probeCacheNode.ab = null // crc based cache lookup only
    probeCacheNode.hash = Crc32.crc(ab, offset, len)

    var cn = cache!!.get(probeCacheNode) as CacheNode?
    if (cn != null) {
      val cab = cn.ab!!
      if (cab.size == len) {
        for (i in 0 until len) {
          if (cab[i] != ab[i + offset]) {
            cn = null
            break
          }
        }
        if (cn != null) {
          lastCacheNode = cn
          return cn.ab!!
        }
      }
    }
    val nab = ByteArray(len)
    ab.copyInto(nab, 0, offset, offset + len)
    return nab
  }


  fun evaluate(inverseDirection: Boolean, ab: ByteArray) {
    requests++
    lookupDataValid = false // this is an assertion for a nasty pifall

    val cache = this.cache
    if (cache == null) {
      decode(lookupData, inverseDirection, ab)
      if (currentVars == null || currentVars!!.size != nBuildInVars) {
        currentVars = FloatArray(nBuildInVars)
      }
      evaluateInto(currentVars!!, 0)
      currentVarOffset = 0
      return
    }

    var cn: CacheNode?
    if (lastCacheNode.ab === ab) {
      cn = lastCacheNode
    } else {
      probeCacheNode.ab = ab
      probeCacheNode.hash = Crc32.crc(ab, 0, ab.size)
      cn = cache.get(probeCacheNode) as CacheNode?
    }

    if (cn == null) {
      cachemisses++

      cn = cache.removeLru() as CacheNode?
      if (cn == null) {
        cn = CacheNode()
      }
      cn.hash = probeCacheNode.hash
      cn.ab = ab
      cache.put(cn)

      if (probeVarSet.vars == null) {
        probeVarSet.vars = FloatArray(2 * nBuildInVars)
      }

      // forward direction
      decode(lookupData, false, ab)
      evaluateInto(probeVarSet.vars!!, 0)

      // inverse direction
      lookupData[0] = 2 // inverse shortcut: reuse decoding
      evaluateInto(probeVarSet.vars!!, nBuildInVars)

      probeVarSet.hash = probeVarSet.vars.contentHashCode()

      // unify the result variable set
      val rvc = resultVarCache!!
      var vw = rvc.get(probeVarSet) as VarWrapper?
      if (vw == null) {
        vw = rvc.removeLru() as VarWrapper?
        if (vw == null) {
          vw = VarWrapper()
        }
        vw.hash = probeVarSet.hash
        vw.vars = probeVarSet.vars
        probeVarSet.vars = null
        rvc.put(vw)
      }
      cn.vars = vw.vars
    } else {
      if (ab === cn.ab) requests2++

      cache.touch(cn)
    }

    currentVars = cn.vars
    currentVarOffset = if (inverseDirection) nBuildInVars else 0
  }

  private fun evaluateInto(vars: FloatArray, offset: Int) {
    evaluate()
    val bidx = buildInVariableIdx!!
    for (vi in 0 until nBuildInVars) {
      val idx = bidx[vi]
      vars[vi + offset] = if (idx == -1) 0f else variableData!![idx]
    }
  }

  /**
   * add a new lookup-value for the given name to the given lookupData array.
   * If no array is given (null value passed), the value is added to
   * the context-binded array. In that case, unknown names and values are
   * created dynamically.
   *
   * @return a newly created value element, if any, to optionally add aliases
   */
  fun addLookupValue(name: String, value: String, lookupData2: IntArray?): BExpressionLookupValue? {
    var newValue: BExpressionLookupValue? = null
    var num = lookupNumbers[name]
    if (num == null) {
      if (lookupData2 != null) {
        // do not create unknown name for external data array
        return newValue
      }

      // unknown name, create
      num = lookupValues.size
      lookupNumbers[name] = num
      lookupNames.add(name)
      lookupValues.add(arrayOf(BExpressionLookupValue(""), BExpressionLookupValue("unknown")))
      lookupHistograms.add(IntArray(2))
      val ndata = IntArray(lookupData.size + 1)
      lookupData.copyInto(ndata, 0, 0, lookupData.size)
      lookupData = ndata
    }

    // look for that value
    var values = lookupValues[num]
    var histo = lookupHistograms[num]
    var i = 0
    while (i < values.size) {
      val v = values[i]
      if (v.matches(value)) break
      i++
    }
    if (i == values.size) {
      if (lookupData2 != null) {
        // do not create unknown value for external data array,
        // record as 'unknown' instead
        lookupData2[num] = 1 // 1 == unknown
        // (upstream then parses raw tag values for a '*' lookup with unit
        // conversions; only map creation passes lookupData2, dropped here)
        return newValue
      }

      if (i == 499) {
        // System.out.println( "value limit reached for: " + name );
      }
      if (i == 500) {
        return newValue
      }
      // unknown value, create
      val nv = BExpressionLookupValue(value)
      newValue = nv
      val oldValues = values
      values = Array(oldValues.size + 1) { if (it < oldValues.size) oldValues[it] else nv }
      val nhisto = IntArray(oldValues.size + 1)
      histo.copyInto(nhisto, 0, 0, histo.size)
      histo = nhisto
      lookupHistograms[num] = histo
      lookupValues[num] = values
    }

    histo[i]++

    // finally remember the actual data
    if (lookupData2 != null) lookupData2[num] = i
    else lookupData[num] = i
    return newValue
  }

  fun getOutputVariableIndex(name: String, mustExist: Boolean): Int {
    val idx = getVariableIdx(name, false)
    if (idx < 0) {
      if (mustExist) {
        throw IllegalArgumentException("unknown variable: $name")
      }
    } else if (idx < minWriteIdx) {
      throw IllegalArgumentException("bad access to global variable: $name")
    }
    val bidx = buildInVariableIdx!!
    for (i in 0 until nBuildInVars) {
      if (bidx[i] == idx) {
        return i
      }
    }
    val extended = IntArray(nBuildInVars + 1)
    bidx.copyInto(extended, 0, 0, nBuildInVars)
    extended[nBuildInVars] = idx
    buildInVariableIdx = extended
    return nBuildInVars++
  }

  fun setForeignContext(foreignContext: BExpressionContext?) {
    this.foreignContext = foreignContext
  }

  fun getForeignVariableValue(foreignIndex: Int): Float {
    return foreignContext!!.getBuildInVariable(foreignIndex)
  }

  fun getForeignVariableIdx(context: String, name: String): Int {
    if (foreignContext == null || context != foreignContext!!.context) {
      throw IllegalArgumentException("unknown foreign context: $context")
    }
    return foreignContext!!.getOutputVariableIndex(name, true)
  }

  fun parseFile(file: String, readOnlyContext: String?) {
    parseFile(file, readOnlyContext, null)
  }

  fun parseFile(file: String, readOnlyContext: String?, keyValues: Map<String, String>?) {
    if (!BFiles.isFile(file)) {
      throw IllegalArgumentException("profile " + fileName(file) + " does not exist")
    }
    try {
      if (readOnlyContext != null) {
        linenr = 1
        val realContext = context
        context = readOnlyContext
        expressionList = _parseFile(file, keyValues)
        variableData = FloatArray(variableNumbers.size)
        evaluate(lookupData) // lookupData is dummy here - evaluate just to create the variables
        context = realContext
      }
      linenr = 1
      minWriteIdx = if (variableData == null) 0 else variableData!!.size
      expressionList = _parseFile(file, null)
      lastAssignedExpression = null

      // determine the build-in variable indices
      val varNames = getBuildInVariableNames()
      nBuildInVars = varNames.size
      val bidx = IntArray(nBuildInVars)
      buildInVariableIdx = bidx
      for (vi in varNames.indices) {
        bidx[vi] = getVariableIdx(varNames[vi], false)
      }

      val readOnlyData = variableData
      val vd = FloatArray(variableNumbers.size)
      variableData = vd
      for (i in 0 until minWriteIdx) {
        vd[i] = readOnlyData!![i]
      }
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("ParseException " + fileName(file) + " at line " + linenr + ": " + e.message)
    } catch (e: Exception) {
      throw RuntimeException(e)
    }
    if (expressionList!!.size == 0) {
      throw IllegalArgumentException(fileName(file)
        + " does not contain expressions for context " + context + " (old version?)")
    }
  }

  private fun _parseFile(file: String, keyValues: Map<String, String>?): MutableList<BExpression> {
    _text = BFiles.readBytes(file).decodeToString()
    _pos = 0
    _readerDone = false
    val result: MutableList<BExpression> = ArrayList()

    // if injected keyValues are present, create assign expressions for them
    if (keyValues != null) {
      for (key in keyValues.keys) {
        val value = keyValues[key]!!
        result.add(BExpression.createAssignExpressionFromKeyValue(this, key, value))
      }
    }

    while (true) {
      val exp = BExpression.parse(this, 0) ?: break
      result.add(exp)
    }
    _text = null
    return result
  }

  fun setVariableValue(name: String, value: Float, create: Boolean) {
    var num = variableNumbers[name]
    if (num != null) {
      variableData!![num] = value
    } else if (create) {
      num = getVariableIdx(name, create)
      val readOnlyData = variableData!!
      val minWriteIdx = readOnlyData.size
      val vd = FloatArray(variableNumbers.size)
      variableData = vd
      for (i in 0 until minWriteIdx) {
        vd[i] = readOnlyData[i]
      }
      vd[num] = value
    }
  }

  fun getVariableValue(name: String, defaultValue: Float): Float {
    val num = variableNumbers[name]
    return if (num == null) defaultValue else getVariableValue(num)
  }

  internal fun getVariableValue(variableIdx: Int): Float {
    return variableData!![variableIdx]
  }

  internal fun getVariableIdx(name: String, create: Boolean): Int {
    var num = variableNumbers[name]
    if (num == null) {
      if (create) {
        num = variableNumbers.size
        variableNumbers[name] = num
        lastAssignedExpression!!.add(null)
      } else {
        return -1
      }
    }
    return num
  }

  internal fun getMinWriteIdx(): Int {
    return minWriteIdx
  }

  internal fun getLookupMatch(nameIdx: Int, valueIdxArray: IntArray): Float {
    for (i in valueIdxArray.indices) {
      if (lookupData[nameIdx] == valueIdxArray[i]) {
        return 1.0f
      }
    }
    return 0.0f
  }

  fun getLookupNameIdx(name: String): Int {
    val num = lookupNumbers[name]
    return num ?: -1
  }

  fun markLookupIdxUsed(idx: Int) {
    lookupIdxUsed!![idx] = true
  }

  fun isLookupIdxUsed(idx: Int): Boolean {
    val used = lookupIdxUsed!!
    return idx < used.size && used[idx]
  }

  fun setAllTagsUsed() {
    val used = lookupIdxUsed!!
    for (i in used.indices) {
      used[i] = true
    }
  }

  fun usedTagList(): String {
    val sb = StringBuilder()
    for (inum in 0 until lookupValues.size) {
      if (lookupIdxUsed!![inum]) {
        if (sb.length > 0) {
          sb.append(',')
        }
        sb.append(lookupNames[inum])
      }
    }
    return sb.toString()
  }

  internal fun getLookupValueIdx(nameIdx: Int, value: String): Int {
    val values = lookupValues[nameIdx]
    for (i in values.indices) {
      if (values[i].equals(value)) return i
    }
    return -1
  }


  internal fun parseToken(): String? {
    while (true) {
      val token = _parseToken() ?: return null
      if (token.startsWith(CONTEXT_TAG)) {
        _inOurContext = token.substring(CONTEXT_TAG.length) == context
      } else if (token.startsWith(MODEL_TAG)) {
        _modelClass = jTrim(token.substring(MODEL_TAG.length))
      } else if (_inOurContext) {
        return token
      }
    }
  }

  // BufferedReader.read(): the next char, or -1 at the end
  private fun _read(): Int {
    val t = _text!!
    return if (_pos < t.length) t[_pos++].code else -1
  }

  private fun _parseToken(): String? {
    val sb = StringBuilder(32)
    val sbcom = StringBuilder(32)
    var inComment = false
    while (true) {
      val ic = if (_readerDone) -1 else _read()
      if (ic < 0) {
        if (sb.length == 0) return null
        _readerDone = true
        return sb.toString()
      }
      val c = ic.toChar()
      if (c == '\n') linenr++

      if (inComment) {
        sbcom.append(c)
        if (c == '\r' || c == '\n') inComment = false
        if (!inComment) {
          val num = variableNumbers["check_start_way"]
          if (num != null && noStartWays.size == 0 && sbcom.toString().contains("noStartWay")) {
            var v = jTrim(sbcom.toString())
            val savar = jSplit(v, '|')
            if (savar.size == 4) {
              v = jTrim(savar[3].substring(savar[3].indexOf("=") + 1))
              val sa = jSplit(v, ';')
              for (s in sa) {
                val sa2 = jSplit(s, ',')
                val name = sa2[0]
                val value = sa2[1]
                val nidx = getLookupNameIdx(name)
                if (nidx == -1) break
                val vidx = getLookupValueIdx(nidx, value)
                val tmp = IntArray(noStartWays.size + 2)
                if (noStartWays.size > 0) noStartWays.copyInto(tmp, 0, 0, noStartWays.size)
                noStartWays = tmp
                noStartWays[noStartWays.size - 2] = nidx
                noStartWays[noStartWays.size - 1] = vidx
              }
            }
          }
          sbcom.clear()
        }
        continue
      }
      if (jIsWhitespace(c)) {
        if (sb.length > 0) return sb.toString()
        else continue
      }
      if (c == '#' && sb.length == 0) inComment = true
      else sb.append(c)
    }
  }

  internal fun assign(variableIdx: Int, value: Float): Float {
    variableData!![variableIdx] = value
    return value
  }

  @JvmField
  internal var ld2 = IntArray(512)

  fun checkStartWay(ab: ByteArray?): Boolean {
    if (ab == null) return true
    ld2.fill(0)
    decode(ld2, false, ab)
    var i = 0
    while (i < noStartWays.size) {
      val key = noStartWays[i]
      val value = noStartWays[i + 1]
      if (ld2[key] == value) return false
      i += 2
    }
    return true
  }

  fun freeNoWays() {
    noStartWays = IntArray(0)
  }

  companion object {
    private const val CONTEXT_TAG = "---context:"
    private const val MODEL_TAG = "---model:"
  }
}
