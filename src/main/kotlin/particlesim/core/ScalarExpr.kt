package particlesim.core

/**
 * A scalar quantity that's either a fixed constant or a function of simulation time
 * (§3, §4.1) — the Kotlin-native building block for "expression-capable" fields
 * (mass, radius, lifetime, ...) ahead of the shared expression parser (Phase 7).
 */
sealed interface ScalarExpr {
    fun evaluate(t: Double): Double

    data class Constant(val value: Double) : ScalarExpr {
        override fun evaluate(t: Double): Double = value
    }

    class OfTime(private val fn: (Double) -> Double) : ScalarExpr {
        override fun evaluate(t: Double): Double = fn(t)
    }

    companion object {
        fun of(value: Double): ScalarExpr = Constant(value)
        fun of(fn: (Double) -> Double): ScalarExpr = OfTime(fn)
    }
}
