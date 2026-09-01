package particlesim.expr

import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionParserTest {

    private fun scalar(source: String, t: Double = 0.0): Double = ExpressionParser.parseScalar(source).evaluate(t)
    private fun vector(source: String, t: Double = 0.0): Vector3 = ExpressionParser.parseVector(source).evaluate(t)

    // --- Arithmetic & precedence -----------------------------------------------------------

    @Test
    fun `basic arithmetic evaluates correctly`() {
        assertEquals(7.0, scalar("1 + 2 * 3"), 1e-12)
        assertEquals(9.0, scalar("(1 + 2) * 3"), 1e-12)
        assertEquals(1.0, scalar("10 / 2 - 4"), 1e-12)
    }

    @Test
    fun `exponentiation is right-associative`() {
        // 2^3^2 = 2^(3^2) = 2^9 = 512, not (2^3)^2 = 64.
        assertEquals(512.0, scalar("2^3^2"), 1e-9)
    }

    @Test
    fun `unary minus binds looser than exponentiation`() {
        // -2^2 = -(2^2) = -4, not (-2)^2 = 4.
        assertEquals(-4.0, scalar("-2^2"), 1e-12)
        assertEquals(4.0, scalar("(-2)^2"), 1e-12)
    }

    @Test
    fun `exponent can itself carry a leading minus`() {
        assertEquals(0.25, scalar("2^-2"), 1e-12)
    }

    @Test
    fun `addition and subtraction are left-associative`() {
        assertEquals(-4.0, scalar("1 - 2 - 3"), 1e-12) // (1-2)-3, not 1-(2-3)=2
    }

    // --- Functions ---------------------------------------------------------------------------

    @Test
    fun `unary functions evaluate correctly`() {
        assertEquals(0.0, scalar("sin(0)"), 1e-12)
        assertEquals(1.0, scalar("cos(0)"), 1e-12)
        assertEquals(3.0, scalar("sqrt(9)"), 1e-12)
        assertEquals(5.0, scalar("abs(-5)"), 1e-12)
    }

    @Test
    fun `min max and clamp evaluate correctly`() {
        assertEquals(2.0, scalar("min(2, 5)"), 1e-12)
        assertEquals(5.0, scalar("max(2, 5)"), 1e-12)
        assertEquals(3.0, scalar("clamp(10, 0, 3)"), 1e-12)
        assertEquals(0.0, scalar("clamp(-10, 0, 3)"), 1e-12)
        assertEquals(1.5, scalar("clamp(1.5, 0, 3)"), 1e-12)
    }

    @Test
    fun `wrong arity is a parse-time error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("sin(1, 2)") }
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("clamp(1, 2)") }
    }

    @Test
    fun `unknown function name is a parse-time error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("bogus(1)") }
    }

    // --- Vectors -------------------------------------------------------------------------------

    @Test
    fun `vector literal evaluates component-wise`() {
        assertEquals(Vector3(1.0, 2.0, 3.0), vector("[1, 2, 3]"))
    }

    @Test
    fun `vector arithmetic is component-wise`() {
        assertEquals(Vector3(5.0, 7.0, 9.0), vector("[1,2,3] + [4,5,6]"))
        assertEquals(Vector3(4.0, 10.0, 18.0), vector("[1,2,3] * [4,5,6]"))
    }

    @Test
    fun `scalar times vector scales it either order`() {
        assertEquals(Vector3(2.0, 4.0, 6.0), vector("2 * [1,2,3]"))
        assertEquals(Vector3(2.0, 4.0, 6.0), vector("[1,2,3] * 2"))
    }

    @Test
    fun `vector divided by scalar scales it`() {
        assertEquals(Vector3(0.5, 1.0, 1.5), vector("[1,2,3] / 2"))
    }

    // --- Type checking (must fail at PARSE time, not evaluation) ----------------------------

    @Test
    fun `adding a scalar to a vector is a parse-time type error`() {
        val ex = assertFailsWith<ExpressionException> { Parser.parse("[1,2,3] + 5") }
        assertTrue(ex.message!!.contains("vector") || ex.message!!.contains("scalar"))
    }

    @Test
    fun `a scalar field given a vector expression is a parse-time type error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("[1,2,3]") }
    }

    @Test
    fun `a vector field given a scalar expression is a parse-time type error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseVector("5.0") }
    }

    @Test
    fun `exponentiation rejects vector operands`() {
        assertFailsWith<ExpressionException> { Parser.parse("[1,2,3]^2") }
    }

    @Test
    fun `dividing a scalar by a vector is a parse-time type error`() {
        assertFailsWith<ExpressionException> { Parser.parse("5 / [1,2,3]") }
    }

    @Test
    fun `function arguments must be scalar`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("sin([1,2,3])") }
    }

    @Test
    fun `unknown identifier is a parse-time error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("x + 1") }
    }

    @Test
    fun `dt is a recognized but not-yet-supported identifier`() {
        // dt is deliberately deferred (see Ast.kt doc) - it should fail clearly, not silently
        // evaluate to some placeholder, and not be indistinguishable from a typo.
        val ex = assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("dt") }
        assertTrue(ex.message!!.contains("dt"))
    }

    // --- Time-varying vs. constant folding ----------------------------------------------------

    @Test
    fun `t evaluates to the given time`() {
        assertEquals(3.5, scalar("t", t = 3.5), 1e-12)
        assertEquals(7.0, scalar("t * 2", t = 3.5), 1e-12)
    }

    @Test
    fun `an expression referencing t produces a ScalarExpr OfTime`() {
        assertTrue(ExpressionParser.parseScalar("t * 2") is ScalarExpr.OfTime)
    }

    @Test
    fun `a constant expression folds to ScalarExpr Constant`() {
        val expr = ExpressionParser.parseScalar("2.0 + 3.0 * 4.0")
        assertTrue(expr is ScalarExpr.Constant)
        assertEquals(14.0, expr.evaluate(999.0), 1e-12) // value is t-independent
    }

    @Test
    fun `a constant vector expression folds to VectorExpr Constant`() {
        val expr = ExpressionParser.parseVector("[1, 2, 3] * 2")
        assertTrue(expr is VectorExpr.Constant)
    }

    @Test
    fun `a time-varying vector expression produces VectorExpr OfTime`() {
        assertTrue(ExpressionParser.parseVector("[t, 0, 0]") is VectorExpr.OfTime)
    }

    @Test
    fun `§10_4 new requirement - parseScalar retains the original source text, for both constant and time-varying results`() {
        val constant = ExpressionParser.parseScalar("2.0 + 3.0")
        val timeVarying = ExpressionParser.parseScalar("2.0 + 0.1 * sin(t)")
        assertEquals("2.0 + 3.0", constant.source)
        assertEquals("2.0 + 0.1 * sin(t)", timeVarying.source)
    }

    @Test
    fun `§10_4 new requirement - parseVector retains the original source text, for both constant and time-varying results`() {
        val constant = ExpressionParser.parseVector("[1, 2, 3] * 2")
        val timeVarying = ExpressionParser.parseVector("[t, 0, 0]")
        assertEquals("[1, 2, 3] * 2", constant.source)
        assertEquals("[t, 0, 0]", timeVarying.source)
    }

    @Test
    fun `a directly-constructed ScalarExpr or VectorExpr has no source - only ExpressionParser sets one`() {
        assertEquals(null, ScalarExpr.of(5.0).source)
        assertEquals(null, ScalarExpr.of { t -> t }.source)
        assertEquals(null, VectorExpr.of(particlesim.core.Vector3.ZERO).source)
    }

    @Test
    fun `§10_4 new requirement - a parsed constant's source doesn't affect ScalarExpr Constant equality`() {
        // A regression guard for exactly the trap this feature could introduce: source is
        // deliberately kept out of Constant's primary constructor (see ScalarExpr's own doc
        // comment) so two constants with the same value stay equal regardless of what source
        // text (if any) either one remembers - SceneControlMessageTest's own
        // SetParticleScalarField/SetEmitterRate round-trip assertions depend on exactly this.
        val parsed = ExpressionParser.parseScalar("9.0") as ScalarExpr.Constant
        val literal = ScalarExpr.Constant(9.0)
        assertEquals(literal, parsed)
        assertEquals(literal.hashCode(), parsed.hashCode())
    }

    // --- noise() ------------------------------------------------------------------------------

    @Test
    fun `noise is deterministic for the same arguments`() {
        assertEquals(scalar("noise(1.23)"), scalar("noise(1.23)"), 0.0)
        assertEquals(scalar("noise(1.23, 4.56)"), scalar("noise(1.23, 4.56)"), 0.0)
    }

    @Test
    fun `noise stays within its expected range`() {
        for (x in -50..50) {
            val v = Noise.eval(doubleArrayOf(x * 0.37))
            assertTrue(v in -1.0..1.0, "noise($x) = $v out of range")
        }
    }

    @Test
    fun `noise is continuous, not a step function, across a lattice boundary`() {
        val a = Noise.eval(doubleArrayOf(0.999))
        val b = Noise.eval(doubleArrayOf(1.0))
        val c = Noise.eval(doubleArrayOf(1.001))
        assertTrue(kotlin.math.abs(a - b) < 0.05 && kotlin.math.abs(b - c) < 0.05, "discontinuity at lattice boundary: $a, $b, $c")
    }

    @Test
    fun `noise takes 1 to 3 arguments`() {
        scalar("noise(t)")
        scalar("noise(t, 1.0)")
        scalar("noise(t, 1.0, 2.0)")
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("noise()") }
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("noise(1,2,3,4)") }
    }

    // --- Lexer edge cases -----------------------------------------------------------------

    @Test
    fun `numbers with decimals and exponents parse correctly`() {
        assertEquals(2.5, scalar("2.5"), 1e-12)
        assertEquals(0.002, scalar("2e-3"), 1e-12)
        assertEquals(200.0, scalar("2e2"), 1e-12)
    }

    @Test
    fun `unexpected trailing input is a parse-time error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("1 + 2 3") }
    }

    @Test
    fun `unexpected character is a parse-time error`() {
        assertFailsWith<ExpressionException> { ExpressionParser.parseScalar("1 & 2") }
    }
}
