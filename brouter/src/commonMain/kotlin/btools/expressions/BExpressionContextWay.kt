// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions

import btools.codec.TagValueValidator

class BExpressionContextWay : BExpressionContext, TagValueValidator {
  private var decodeForbidden = true

  override fun getBuildInVariableNames(): Array<String> {
    return buildInVariables
  }

  fun getCostfactor(): Float {
    return getBuildInVariable(0)
  }

  fun getTurncost(): Float {
    return getBuildInVariable(1)
  }

  fun getUphillCostfactor(): Float {
    return getBuildInVariable(2)
  }

  fun getDownhillCostfactor(): Float {
    return getBuildInVariable(3)
  }

  fun getInitialcost(): Float {
    return getBuildInVariable(4)
  }

  fun getNodeAccessGranted(): Float {
    return getBuildInVariable(5)
  }

  fun getInitialClassifier(): Float {
    return getBuildInVariable(6)
  }

  fun getTrafficSourceDensity(): Float {
    return getBuildInVariable(7)
  }

  fun getIsTrafficBackbone(): Float {
    return getBuildInVariable(8)
  }

  fun getPriorityClassifier(): Float {
    return getBuildInVariable(9)
  }

  fun getClassifierMask(): Float {
    return getBuildInVariable(10)
  }

  fun getMaxspeed(): Float {
    return getBuildInVariable(11)
  }

  fun getUphillcost(): Float {
    return getBuildInVariable(12)
  }

  fun getDownhillcost(): Float {
    return getBuildInVariable(13)
  }

  fun getUphillcutoff(): Float {
    return getBuildInVariable(14)
  }

  fun getDownhillcutoff(): Float {
    return getBuildInVariable(15)
  }

  fun getUphillmaxslope(): Float {
    return getBuildInVariable(16)
  }

  fun getDownhillmaxslope(): Float {
    return getBuildInVariable(17)
  }

  fun getUphillmaxslopecost(): Float {
    return getBuildInVariable(18)
  }

  fun getDownhillmaxslopecost(): Float {
    return getBuildInVariable(19)
  }

  constructor(meta: BExpressionMetaData?) : super("way", meta)

  /**
   * Create an Expression-Context for way context
   *
   * @param hashSize size of hashmap for result caching
   */
  constructor(hashSize: Int, meta: BExpressionMetaData?) : super("way", hashSize, meta)

  override fun accessType(description: ByteArray): Int {
    evaluate(false, description)
    var minCostFactor = getCostfactor()
    if (minCostFactor >= 9999f) {
      setInverseVars()
      val reverseCostFactor = getCostfactor()
      if (reverseCostFactor < minCostFactor) {
        minCostFactor = reverseCostFactor
      }
    }
    return if (minCostFactor < 9999f) 2 else if (decodeForbidden) (if (minCostFactor < 10000f) 1 else 0) else 0
  }

  override fun setDecodeForbidden(decodeForbidden: Boolean) {
    this.decodeForbidden = decodeForbidden
  }

  companion object {
    private val buildInVariables: Array<String> =
      arrayOf("costfactor", "turncost", "uphillcostfactor", "downhillcostfactor", "initialcost", "nodeaccessgranted", "initialclassifier", "trafficsourcedensity", "istrafficbackbone", "priorityclassifier", "classifiermask", "maxspeed", "uphillcost", "downhillcost", "uphillcutoff", "downhillcutoff", "uphillmaxslope", "downhillmaxslope", "uphillmaxslopecost", "downhillmaxslopecost")
  }

}
