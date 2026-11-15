package btools.expressions

import btools.util.jParseFloat
import btools.util.tokenize

internal class BExpression {
  private var typ: Int = 0
  private var op1: BExpression? = null
  private var op2: BExpression? = null
  private var op3: BExpression? = null
  private var numberValue: Float = 0f
  private var variableIdx: Int = 0
  private var lookupNameIdx: Int = -1
  private var lookupValueIdxArray: IntArray? = null
  private var doNotChange: Boolean = false

  private fun markLookupIdxUsed(ctx: BExpressionContext): Int {
    var nodeCount = 1
    if (lookupNameIdx >= 0) {
      ctx.markLookupIdxUsed(lookupNameIdx)
    }
    if (op1 != null) {
      nodeCount += op1!!.markLookupIdxUsed(ctx)
    }
    if (op2 != null) {
      nodeCount += op2!!.markLookupIdxUsed(ctx)
    }
    if (op3 != null) {
      nodeCount += op3!!.markLookupIdxUsed(ctx)
    }
    return nodeCount
  }

  // Evaluate the expression
  fun evaluate(ctx: BExpressionContext?): Float {
    when (typ) {
      OR_EXP ->
        return if (op1!!.evaluate(ctx) != 0f) 1f else (if (op2!!.evaluate(ctx) != 0f) 1f else 0f)
      XOR_EXP ->
        return if ((op1!!.evaluate(ctx) != 0f) xor (op2!!.evaluate(ctx) != 0f)) 1f else 0f
      AND_EXP ->
        return if (op1!!.evaluate(ctx) != 0f) (if (op2!!.evaluate(ctx) != 0f) 1f else 0f) else 0f
      ADD_EXP ->
        return op1!!.evaluate(ctx) + op2!!.evaluate(ctx)
      SUB_EXP ->
        return op1!!.evaluate(ctx) - op2!!.evaluate(ctx)
      MULTIPLY_EXP ->
        return op1!!.evaluate(ctx) * op2!!.evaluate(ctx)
      DIVIDE_EXP ->
        return divide(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
      MAX_EXP ->
        return max(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
      MIN_EXP ->
        return min(op1!!.evaluate(ctx), op2!!.evaluate(ctx))
      EQUAL_EXP ->
        return if (op1!!.evaluate(ctx) == op2!!.evaluate(ctx)) 1f else 0f
      GREATER_EXP ->
        return if (op1!!.evaluate(ctx) > op2!!.evaluate(ctx)) 1f else 0f
      LESSER_EXP ->
        return if (op1!!.evaluate(ctx) < op2!!.evaluate(ctx)) 1f else 0f
      SWITCH_EXP ->
        return if (op1!!.evaluate(ctx) != 0f) op2!!.evaluate(ctx) else op3!!.evaluate(ctx)
      ASSIGN_EXP ->
        return ctx!!.assign(variableIdx, op1!!.evaluate(ctx))
      LOOKUP_EXP ->
        return ctx!!.getLookupMatch(lookupNameIdx, lookupValueIdxArray!!)
      NUMBER_EXP ->
        return numberValue
      VARIABLE_EXP ->
        return ctx!!.getVariableValue(variableIdx)
      FOREIGN_VARIABLE_EXP ->
        return ctx!!.getForeignVariableValue(variableIdx)
      VARIABLE_GET_EXP ->
        return ctx!!.getLookupValue(lookupNameIdx)
      NOT_EXP ->
        return if (op1!!.evaluate(ctx) == 0f) 1f else 0f
      else ->
        throw IllegalArgumentException("unknown op-code: $typ")
    }
  }

  // Try to collapse the expression
  // if logically possible
  private fun tryCollapse(): BExpression {
    when (typ) {
      OR_EXP ->
        return if (NUMBER_EXP == op1!!.typ)
          (if (op1!!.numberValue != 0f) op1!! else op2!!)
        else (if (NUMBER_EXP == op2!!.typ)
          (if (op2!!.numberValue != 0f) op2!! else op1!!)
        else this)
      AND_EXP ->
        return if (NUMBER_EXP == op1!!.typ)
          (if (op1!!.numberValue == 0f) op1!! else op2!!)
        else (if (NUMBER_EXP == op2!!.typ)
          (if (op2!!.numberValue == 0f) op2!! else op1!!)
        else this)
      ADD_EXP ->
        return if (NUMBER_EXP == op1!!.typ)
          (if (op1!!.numberValue == 0f) op2!! else this)
        else (if (NUMBER_EXP == op2!!.typ)
          (if (op2!!.numberValue == 0f) op1!! else this)
        else this)
      SWITCH_EXP ->
        return if (NUMBER_EXP == op1!!.typ)
          (if (op1!!.numberValue == 0f) op3!! else op2!!) else this
      else ->
        return this
    }
  }

  // Try to evaluate the expression
  // if all operands are constant
  private fun tryEvaluateConstant(): BExpression {
    if (op1 != null && NUMBER_EXP == op1!!.typ
      && (op2 == null || NUMBER_EXP == op2!!.typ)
      && (op3 == null || NUMBER_EXP == op3!!.typ)) {
      val exp = BExpression()
      exp.typ = NUMBER_EXP
      exp.numberValue = evaluate(null)
      return exp
    }
    return this
  }

  private fun max(v1: Float, v2: Float): Float {
    return if (v1 > v2) v1 else v2
  }

  private fun min(v1: Float, v2: Float): Float {
    return if (v1 < v2) v1 else v2
  }

  private fun divide(v1: Float, v2: Float): Float {
    if (v2 == 0f) throw IllegalArgumentException("div by zero")
    return v1 / v2
  }

  override fun toString(): String {
    if (typ == NUMBER_EXP) {
      return "" + numberValue.toString()
    }
    if (typ == VARIABLE_EXP) {
      return "vidx=$variableIdx"
    }
    val sb = StringBuilder("typ=$typ ops=(")
    addOp(sb, op1)
    addOp(sb, op2)
    addOp(sb, op3)
    sb.append(')')
    return sb.toString()
  }

  private fun addOp(sb: StringBuilder, e: BExpression?) {
    if (e != null) {
      sb.append('[').append(e.toString()).append(']')
    }
  }

  companion object {
    private const val OR_EXP = 10
    private const val AND_EXP = 11
    private const val NOT_EXP = 12

    private const val ADD_EXP = 20
    private const val MULTIPLY_EXP = 21
    private const val DIVIDE_EXP = 22
    private const val MAX_EXP = 23
    private const val EQUAL_EXP = 24
    private const val GREATER_EXP = 25
    private const val MIN_EXP = 26

    private const val SUB_EXP = 27
    private const val LESSER_EXP = 28
    private const val XOR_EXP = 29

    private const val SWITCH_EXP = 30
    private const val ASSIGN_EXP = 31
    private const val LOOKUP_EXP = 32
    private const val NUMBER_EXP = 33
    private const val VARIABLE_EXP = 34
    private const val FOREIGN_VARIABLE_EXP = 35
    private const val VARIABLE_GET_EXP = 36

    // Parse the expression and all subexpression
    fun parse(ctx: BExpressionContext, level: Int): BExpression? {
      return parse(ctx, level, null)
    }

    private fun parse(ctx: BExpressionContext, level: Int, optionalToken: String?): BExpression? {
      var e = parseRaw(ctx, level, optionalToken)
      if (e == null) {
        return null
      }

      if (ASSIGN_EXP == e.typ) {
        // manage assined an injected values
        val assignedBefore = ctx.lastAssignedExpression!![e.variableIdx]
        if (assignedBefore != null && assignedBefore.doNotChange) {
          e.op1 = assignedBefore // was injected as key-value
          e.op1!!.doNotChange = false // protect just once, can be changed in second assignement
        }
        ctx.lastAssignedExpression!![e.variableIdx] = e.op1
      } else if (!ctx.skipConstantExpressionOptimizations) {
        // try to simplify the expression
        if (VARIABLE_EXP == e.typ) {
          val ae = ctx.lastAssignedExpression!![e.variableIdx]
          if (ae != null && ae.typ == NUMBER_EXP) {
            e = ae
          }
        } else {
          val eCollapsed = e.tryCollapse()
          if (e !== eCollapsed) {
            e = eCollapsed // allow breakpoint..
          }
          val eEvaluated = e.tryEvaluateConstant()
          if (e !== eEvaluated) {
            e = eEvaluated // allow breakpoint..
          }
        }
      }
      if (level == 0) {
        // mark the used lookups after the
        // expression is collapsed to not mark
        // lookups as used that appear in the profile
        // but are de-activated by constant expressions
        val nodeCount = e.markLookupIdxUsed(ctx)
        ctx.expressionNodeCount += nodeCount
      }
      return e
    }

    private fun parseRaw(ctx: BExpressionContext, level: Int, optionalToken: String?): BExpression? {

      var brackets = false
      var operator = ctx.parseToken()
      if (optionalToken != null && optionalToken == operator) {
        operator = ctx.parseToken()
      }
      if ("(" == operator) {
        brackets = true
        operator = ctx.parseToken()
      }

      if (operator == null) {
        if (level == 0) return null
        else throw IllegalArgumentException("unexpected end of file")
      }

      if (level == 0) {
        if ("assign" != operator) {
          throw IllegalArgumentException("operator $operator is invalid on toplevel (only 'assign' allowed)")
        }
      }

      val exp = BExpression()
      var nops = 3

      var ifThenElse = false

      if ("switch" == operator) {
        exp.typ = SWITCH_EXP
      } else if ("if" == operator) {
        exp.typ = SWITCH_EXP
        ifThenElse = true
      } else {
        nops = 2 // check binary expressions

        if ("or" == operator) {
          exp.typ = OR_EXP
        } else if ("and" == operator) {
          exp.typ = AND_EXP
        } else if ("multiply" == operator) {
          exp.typ = MULTIPLY_EXP
        } else if ("divide" == operator) {
          exp.typ = DIVIDE_EXP
        } else if ("add" == operator) {
          exp.typ = ADD_EXP
        } else if ("max" == operator) {
          exp.typ = MAX_EXP
        } else if ("min" == operator) {
          exp.typ = MIN_EXP
        } else if ("equal" == operator) {
          exp.typ = EQUAL_EXP
        } else if ("greater" == operator) {
          exp.typ = GREATER_EXP
        } else if ("sub" == operator) {
          exp.typ = SUB_EXP
        } else if ("lesser" == operator) {
          exp.typ = LESSER_EXP
        } else if ("xor" == operator) {
          exp.typ = XOR_EXP
        } else {
          nops = 1 // check unary expressions
          if ("assign" == operator) {
            if (level > 0) throw IllegalArgumentException("assign operator within expression")
            exp.typ = ASSIGN_EXP
            val variable = ctx.parseToken()
            if (variable == null) throw IllegalArgumentException("unexpected end of file")
            if (variable.indexOf('=') >= 0)
              throw IllegalArgumentException("variable name cannot contain '=': $variable")
            if (variable.indexOf(':') >= 0)
              throw IllegalArgumentException("cannot assign context-prefixed variable: $variable")
            exp.variableIdx = ctx.getVariableIdx(variable, true)
            if (exp.variableIdx < ctx.getMinWriteIdx())
              throw IllegalArgumentException("cannot assign to readonly variable $variable")
          } else if ("not" == operator) {
            exp.typ = NOT_EXP
          } else {
            nops = 0 // check elemantary expressions
            var idx = operator.indexOf('=')
            if (idx >= 0) {
              exp.typ = LOOKUP_EXP
              val name = operator.substring(0, idx)
              val values = operator.substring(idx + 1)

              exp.lookupNameIdx = ctx.getLookupNameIdx(name)
              if (exp.lookupNameIdx < 0) {
                throw IllegalArgumentException("unknown lookup name: $name")
              }
              val tk = tokenize(values, "|")
              val nt = tk.size
              val nt2 = if (nt == 0) 1 else nt
              val lva = IntArray(nt2)
              exp.lookupValueIdxArray = lva
              for (ti in 0 until nt2) {
                val value = if (ti < nt) tk[ti] else ""
                lva[ti] = ctx.getLookupValueIdx(exp.lookupNameIdx, value)
                if (lva[ti] < 0) {
                  throw IllegalArgumentException("unknown lookup value: $value")
                }
              }
            } else if (run { idx = operator.indexOf(':'); idx } >= 0) {
              /*
              use of variable values
              assign no_height
                 switch and not      maxheight=
                          lesser v:maxheight  my_height  true
              false
               */
              if (operator.startsWith("v:")) {
                val name = operator.substring(2)
                exp.typ = VARIABLE_GET_EXP
                exp.lookupNameIdx = ctx.getLookupNameIdx(name)
              } else {
                val context = operator.substring(0, idx)
                val varname = operator.substring(idx + 1)
                exp.typ = FOREIGN_VARIABLE_EXP
                exp.variableIdx = ctx.getForeignVariableIdx(context, varname)
              }
            } else if (run { idx = ctx.getVariableIdx(operator, false); idx } >= 0) {
              exp.typ = VARIABLE_EXP
              exp.variableIdx = idx
            } else if ("true" == operator) {
              exp.numberValue = 1f
              exp.typ = NUMBER_EXP
            } else if ("false" == operator) {
              exp.numberValue = 0f
              exp.typ = NUMBER_EXP
            } else {
              try {
                exp.numberValue = jParseFloat(operator)
                exp.typ = NUMBER_EXP
              } catch (nfe: NumberFormatException) {
                throw IllegalArgumentException("unknown expression: $operator")
              }
            }
          }
        }
      }
      // parse operands
      if (nops > 0) {
        exp.op1 = parse(ctx, level + 1, if (exp.typ == ASSIGN_EXP) "=" else null)
      }
      if (nops > 1) {
        if (ifThenElse) checkExpectedToken(ctx, "then")
        exp.op2 = parse(ctx, level + 1, null)
      }
      if (nops > 2) {
        if (ifThenElse) checkExpectedToken(ctx, "else")
        exp.op3 = parse(ctx, level + 1, null)
      }
      if (brackets) {
        checkExpectedToken(ctx, ")")
      }
      return exp
    }

    private fun checkExpectedToken(ctx: BExpressionContext, expected: String) {
      val token = ctx.parseToken()
      if (expected != token) {
        throw IllegalArgumentException("unexpected token: $token, expected: $expected")
      }
    }

    internal fun createAssignExpressionFromKeyValue(ctx: BExpressionContext, key: String, value: String): BExpression {
      val e = BExpression()
      e.typ = ASSIGN_EXP
      e.variableIdx = ctx.getVariableIdx(key, true)
      val op = BExpression()
      e.op1 = op
      op.typ = NUMBER_EXP
      op.numberValue = jParseFloat(value)
      op.doNotChange = true
      ctx.lastAssignedExpression!![e.variableIdx] = e.op1
      return e
    }
  }
}
