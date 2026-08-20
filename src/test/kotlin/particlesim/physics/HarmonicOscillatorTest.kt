package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** §15.1: a single spring-mass system's period should converge toward `2π√(m/k)` as `dt` shrinks. */
class HarmonicOscillatorTest {

    private fun measuredPeriod(dt: Double, mass: Double, stiffness: Double, amplitude: Double): Double {
        val store = ParticleStore()
        val groups = Groups()
        val anchor = store.create(position = Vector3.ZERO)
        val bob = store.create(position = Vector3(amplitude, 0.0, 0.0), mass = particlesim.core.ScalarExpr.of(mass))
        groups.add("anchor", anchor)

        val spring = Spring(anchor, bob, restLength = 0.0, stiffness = stiffness)
        val constraints = listOf(FixedPosition("anchor", Vector3.ZERO))
        val integrator = Integrator()

        var t = 0.0
        var prevX = store.position(bob).x
        var crossings = 0
        var firstCrossingT = 0.0
        var lastCrossingT = 0.0

        // Run long enough to observe several zero-crossings of x (displacement from anchor),
        // each half-period apart.
        val maxSteps = (20.0 * 2.0 * PI * sqrt(mass / stiffness) / dt).toInt()
        for (step in 0 until maxSteps) {
            integrator.step(store, groups, listOf(spring), constraints, t, dt)
            t += dt
            val x = store.position(bob).x
            if (prevX > 0.0 && x <= 0.0 || prevX < 0.0 && x >= 0.0) {
                crossings++
                if (crossings == 1) firstCrossingT = t
                lastCrossingT = t
            }
            prevX = x
            if (crossings >= 11) break
        }

        assertTrue(crossings >= 11, "expected the spring to oscillate through 11 zero-crossings, got $crossings")

        // Each successive crossing is half a period apart.
        val halfPeriods = crossings - 1
        return 2.0 * (lastCrossingT - firstCrossingT) / halfPeriods
    }

    @Test
    fun `period converges to 2 pi sqrt(m over k) as dt shrinks`() {
        val mass = 2.0
        val stiffness = 50.0
        val expected = 2.0 * PI * sqrt(mass / stiffness)

        val coarse = measuredPeriod(dt = 1e-3, mass = mass, stiffness = stiffness, amplitude = 1.0)
        val fine = measuredPeriod(dt = 1e-5, mass = mass, stiffness = stiffness, amplitude = 1.0)

        val coarseError = kotlin.math.abs(coarse - expected) / expected
        val fineError = kotlin.math.abs(fine - expected) / expected

        assertTrue(fineError < coarseError, "finer dt ($fineError) should be more accurate than coarser dt ($coarseError)")
        assertTrue(fineError < 1e-3, "expected $expected, measured $fine")
    }
}
