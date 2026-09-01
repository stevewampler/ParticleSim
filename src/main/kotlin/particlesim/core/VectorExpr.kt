package particlesim.core

/**
 * A vector quantity that's either a fixed constant or a function of simulation time — the
 * [Vector3] counterpart to [ScalarExpr], same constant-vs-time-varying shape (see [ScalarExpr]'s
 * own doc comment for what [source] is and why it's kept out of [Constant]'s equality).
 *
 * Scoped to *time only*, matching what's actually needed today: §7.3's flag example calls
 * for wind that varies in time, and §5.2 marks position-dependence optional ("and/or
 * position"). An earlier note (Phase 2's TODO) assumed wind was blocked on a position-aware
 * expression type before building this — that turned out to be wrong once the actual
 * consumer (§7.3) was in view; spatially-varying wind (gusts that differ across the sheet)
 * remains unbuilt, deferred until something concretely needs it rather than guessed at now.
 */
sealed interface VectorExpr {
    fun evaluate(t: Double): Vector3
    val source: String?

    data class Constant(val value: Vector3) : VectorExpr {
        override var source: String? = null
            internal set
        override fun evaluate(t: Double): Vector3 = value
    }

    class OfTime(private val fn: (Double) -> Vector3, override val source: String? = null) : VectorExpr {
        override fun evaluate(t: Double): Vector3 = fn(t)
    }

    companion object {
        fun of(value: Vector3): VectorExpr = Constant(value)
        fun of(fn: (Double) -> Vector3): VectorExpr = OfTime(fn)

        /** [particlesim.expr.ExpressionParser]'s entry point for a source-carrying constant -
         * see [ScalarExpr]'s own doc comment for why [source] isn't a [Constant] constructor
         * parameter. */
        fun of(value: Vector3, source: String): VectorExpr = Constant(value).also { it.source = source }
    }
}
