package particlesim.expr

import particlesim.core.Vector3

enum class ValueType { SCALAR, VECTOR }

/** An evaluated expression result — a node's [ExprNode.type] says which case to expect. */
sealed interface Value {
    data class Num(val v: Double) : Value
    data class Vec(val v: Vector3) : Value
}

/**
 * One node of a parsed expression's AST (§4.1). [type] and [isConstant] are both resolved
 * *structurally*, at construction time — this is what makes scalar/vector type errors and
 * the constant-vs-time-varying distinction parse-time facts rather than something discovered
 * on first [evaluate]. `t` is the only free variable currently wired to a real value; `dt`
 * (and any future entity-state variables — position/velocity/id) aren't reachable yet because
 * nothing in [particlesim.core.ScalarExpr]/[particlesim.core.VectorExpr]'s `evaluate(t)`
 * signature threads them through — widening that is a real but deliberately deferred change,
 * not an oversight (see TODO.md).
 */
sealed interface ExprNode {
    val type: ValueType
    val isConstant: Boolean
    fun evaluate(t: Double): Value
}

data class NumberLiteral(val value: Double) : ExprNode {
    override val type = ValueType.SCALAR
    override val isConstant = true
    override fun evaluate(t: Double): Value = Value.Num(value)
}

data class TimeVariable(val name: String) : ExprNode {
    override val type = ValueType.SCALAR
    override val isConstant = false
    override fun evaluate(t: Double): Value = Value.Num(t)
}

data class VectorLiteral(val x: ExprNode, val y: ExprNode, val z: ExprNode) : ExprNode {
    init {
        require(x.type == ValueType.SCALAR && y.type == ValueType.SCALAR && z.type == ValueType.SCALAR) {
            "vector literal components must be scalar"
        }
    }
    override val type = ValueType.VECTOR
    override val isConstant = x.isConstant && y.isConstant && z.isConstant
    override fun evaluate(t: Double): Value {
        val xv = (x.evaluate(t) as Value.Num).v
        val yv = (y.evaluate(t) as Value.Num).v
        val zv = (z.evaluate(t) as Value.Num).v
        return Value.Vec(Vector3(xv, yv, zv))
    }
}

enum class BinaryOp { ADD, SUB, MUL, DIV, POW }

class BinaryOpNode(val op: BinaryOp, val left: ExprNode, val right: ExprNode) : ExprNode {
    override val type: ValueType
    override val isConstant: Boolean = left.isConstant && right.isConstant

    init {
        type = when (op) {
            BinaryOp.ADD, BinaryOp.SUB -> {
                if (left.type != right.type) {
                    throw ExpressionException(
                        "type mismatch: cannot ${if (op == BinaryOp.ADD) "add" else "subtract"} " +
                            "${describe(left.type)} and ${describe(right.type)}",
                    )
                }
                left.type
            }
            BinaryOp.MUL, BinaryOp.DIV -> {
                when {
                    left.type == ValueType.SCALAR && right.type == ValueType.SCALAR -> ValueType.SCALAR
                    left.type == ValueType.VECTOR && right.type == ValueType.VECTOR -> ValueType.VECTOR
                    left.type == ValueType.VECTOR && right.type == ValueType.SCALAR -> ValueType.VECTOR
                    left.type == ValueType.SCALAR && right.type == ValueType.VECTOR && op == BinaryOp.MUL -> ValueType.VECTOR
                    else -> throw ExpressionException(
                        "type mismatch: cannot ${if (op == BinaryOp.MUL) "multiply" else "divide"} " +
                            "${describe(left.type)} by ${describe(right.type)}",
                    )
                }
            }
            BinaryOp.POW -> {
                if (left.type != ValueType.SCALAR || right.type != ValueType.SCALAR) {
                    throw ExpressionException("type mismatch: '^' requires scalar operands")
                }
                ValueType.SCALAR
            }
        }
    }

    override fun evaluate(t: Double): Value {
        val l = left.evaluate(t)
        val r = right.evaluate(t)
        return when (op) {
            BinaryOp.ADD -> combine(l, r) { a, b -> a + b }
            BinaryOp.SUB -> combine(l, r) { a, b -> a - b }
            BinaryOp.MUL -> when {
                l is Value.Num && r is Value.Num -> Value.Num(l.v * r.v)
                l is Value.Vec && r is Value.Vec -> Value.Vec(Vector3(l.v.x * r.v.x, l.v.y * r.v.y, l.v.z * r.v.z))
                l is Value.Vec && r is Value.Num -> Value.Vec(l.v * r.v)
                l is Value.Num && r is Value.Vec -> Value.Vec(r.v * l.v)
                else -> error("unreachable: type-checked at parse time")
            }
            BinaryOp.DIV -> when {
                l is Value.Num && r is Value.Num -> Value.Num(l.v / r.v)
                l is Value.Vec && r is Value.Vec -> Value.Vec(Vector3(l.v.x / r.v.x, l.v.y / r.v.y, l.v.z / r.v.z))
                l is Value.Vec && r is Value.Num -> Value.Vec(l.v * (1.0 / r.v))
                else -> error("unreachable: type-checked at parse time")
            }
            BinaryOp.POW -> Value.Num(Math.pow((l as Value.Num).v, (r as Value.Num).v))
        }
    }

    private inline fun combine(l: Value, r: Value, op: (Double, Double) -> Double): Value = when {
        l is Value.Num && r is Value.Num -> Value.Num(op(l.v, r.v))
        l is Value.Vec && r is Value.Vec -> Value.Vec(Vector3(op(l.v.x, r.v.x), op(l.v.y, r.v.y), op(l.v.z, r.v.z)))
        else -> error("unreachable: type-checked at parse time")
    }

    private fun describe(t: ValueType) = if (t == ValueType.SCALAR) "a scalar" else "a vector"
}

class UnaryMinusNode(val operand: ExprNode) : ExprNode {
    override val type = operand.type
    override val isConstant = operand.isConstant
    override fun evaluate(t: Double): Value = when (val v = operand.evaluate(t)) {
        is Value.Num -> Value.Num(-v.v)
        is Value.Vec -> Value.Vec(-v.v)
    }
}

/** A named function call — `sin`/`cos`/`tan`/`sqrt`/`abs` (1 scalar arg), `min`/`max` (2),
 * `clamp` (3), `noise` (1-3). All results are scalar; all arguments must be scalar too, since
 * none of these operate component-wise on a vector in this grammar. */
class FunctionCallNode(val name: String, val args: List<ExprNode>) : ExprNode {
    override val type = ValueType.SCALAR
    override val isConstant = args.all { it.isConstant }

    init {
        val badArg = args.indexOfFirst { it.type != ValueType.SCALAR }
        if (badArg >= 0) throw ExpressionException("$name(): argument ${badArg + 1} must be scalar, got a vector")
        val arity = ARITY[name] ?: throw ExpressionException("unknown function '$name'")
        val ok = when (name) {
            "noise" -> args.size in 1..3
            else -> args.size == arity
        }
        if (!ok) throw ExpressionException("$name() expects ${arity(name)} argument(s), got ${args.size}")
    }

    private fun arity(name: String) = if (name == "noise") "1 to 3" else ARITY[name].toString()

    override fun evaluate(t: Double): Value {
        val a = args.map { (it.evaluate(t) as Value.Num).v }
        val result = when (name) {
            "sin" -> kotlin.math.sin(a[0])
            "cos" -> kotlin.math.cos(a[0])
            "tan" -> kotlin.math.tan(a[0])
            "sqrt" -> kotlin.math.sqrt(a[0])
            "abs" -> kotlin.math.abs(a[0])
            "min" -> kotlin.math.min(a[0], a[1])
            "max" -> kotlin.math.max(a[0], a[1])
            "clamp" -> a[0].coerceIn(minOf(a[1], a[2]), maxOf(a[1], a[2]))
            "noise" -> Noise.eval(a.toDoubleArray())
            else -> error("unreachable: unknown function already rejected in init")
        }
        return Value.Num(result)
    }

    companion object {
        private val ARITY = mapOf(
            "sin" to 1, "cos" to 1, "tan" to 1, "sqrt" to 1, "abs" to 1,
            "min" to 2, "max" to 2, "clamp" to 3, "noise" to 1,
        )
    }
}
