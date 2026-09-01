package particlesim.core

/**
 * A scalar quantity that's either a fixed constant or a function of simulation time
 * (§3, §4.1) — the Kotlin-native building block for "expression-capable" fields
 * (mass, radius, lifetime, ...) ahead of the shared expression parser (Phase 7).
 *
 * [source] is the original expression-string source this value was parsed from (§10.4, new
 * requirement: "show a field's current expression source, not just its live evaluated value"),
 * or `null` for a value built directly in Kotlin (a literal via [of], or a native DSL lambda) -
 * there is no string to show back in that case. Only [particlesim.expr.ExpressionParser] ever
 * sets it, via the two-argument [of] overload below. Deliberately kept **out of [Constant]'s
 * primary constructor** even though [Constant] is otherwise a `data class`: putting it there
 * would fold [source] into the generated `equals`/`hashCode`, silently breaking every existing
 * `assertEquals` that compares a parser-produced constant against a directly-constructed one
 * (e.g. `SceneControlMessageTest`'s `SetParticleScalarField`/`SetEmitterRate` round-trip
 * assertions) - two [Constant]s with the same [value] must stay equal regardless of what source
 * text (if any) either one remembers.
 */
sealed interface ScalarExpr {
    fun evaluate(t: Double): Double
    val source: String?

    data class Constant(val value: Double) : ScalarExpr {
        override var source: String? = null
            internal set
        override fun evaluate(t: Double): Double = value
    }

    class OfTime(private val fn: (Double) -> Double, override val source: String? = null) : ScalarExpr {
        override fun evaluate(t: Double): Double = fn(t)
    }

    companion object {
        fun of(value: Double): ScalarExpr = Constant(value)
        fun of(fn: (Double) -> Double): ScalarExpr = OfTime(fn)

        /** [particlesim.expr.ExpressionParser]'s entry point for a source-carrying constant -
         * see this interface's own doc comment for why [source] isn't a [Constant] constructor
         * parameter. */
        fun of(value: Double, source: String): ScalarExpr = Constant(value).also { it.source = source }
    }
}
