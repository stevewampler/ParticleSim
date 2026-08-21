package particlesim.expr

import particlesim.core.ScalarExpr
import particlesim.core.VectorExpr

/**
 * Public entry point bridging §4.1's parsed expression grammar into the *exact* types every
 * force/constraint/collider/emitter already consumes ([ScalarExpr]/[VectorExpr]) — this is
 * what makes a parsed YAML expression string a drop-in replacement for a Kotlin lambda
 * wherever one is already accepted, rather than a parallel evaluation path.
 *
 * A parsed expression that never references `t` is folded to [ScalarExpr.Constant]/
 * [VectorExpr.Constant] rather than [ScalarExpr.OfTime]/[VectorExpr.OfTime] — matching what a
 * literal `2.0` already gets (e.g. [particlesim.core.ParticleStore.hasDynamicMass] keys off
 * exactly this distinction), so `"2.0 + 3.0"` behaves identically to writing `5.0` directly.
 */
object ExpressionParser {
    fun parseScalar(source: String): ScalarExpr {
        val node = Parser.parse(source)
        if (node.type != ValueType.SCALAR) {
            throw ExpressionException("expected a scalar expression but \"$source\" is a vector")
        }
        return if (node.isConstant) {
            ScalarExpr.of((node.evaluate(0.0) as Value.Num).v)
        } else {
            ScalarExpr.of { t -> (node.evaluate(t) as Value.Num).v }
        }
    }

    fun parseVector(source: String): VectorExpr {
        val node = Parser.parse(source)
        if (node.type != ValueType.VECTOR) {
            throw ExpressionException("expected a vector expression but \"$source\" is a scalar")
        }
        return if (node.isConstant) {
            VectorExpr.of((node.evaluate(0.0) as Value.Vec).v)
        } else {
            VectorExpr.of { t -> (node.evaluate(t) as Value.Vec).v }
        }
    }
}
