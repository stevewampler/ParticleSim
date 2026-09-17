package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §15.3: SPH density/pressure/viscosity for known inputs, in isolation - no running simulation. */
class SphFluidTest {

    private fun netForceOn(id: Int, store: ParticleStore, groups: Groups, force: SphFluid): Vector3 {
        val chunk = ChunkAccumulator(store.capacity)
        force.accumulate(store, groups, 0.0, chunk, 0, 1)
        return chunk.at(store.slotOf(id))
    }

    private fun assertVectorEquals(expected: Vector3, actual: Vector3, epsilon: Double = 1e-9) {
        assertTrue(
            abs(expected.x - actual.x) < epsilon && abs(expected.y - actual.y) < epsilon && abs(expected.z - actual.z) < epsilon,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun `measureDensity for a single particle matches the poly6 kernel evaluated at its own position`() {
        val h = 0.2
        val mass = 1.5
        // r = 0: the kernel's own value at its center, independent of accumulate's neighbor
        // search - pins the poly6 coefficient itself, the easiest thing here to typo unnoticed.
        val expected = mass * (315.0 / (64.0 * PI * h.pow(9))) * h.pow(6)
        val measured = SphFluid.measureDensity(listOf(Vector3.ZERO), Vector3.ZERO, mass, h)
        assertEquals(expected, measured, 1e-9)
    }

    @Test
    fun `two particles farther apart than the smoothing radius exert no force on each other`() {
        val h = 0.2
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0), mass = ScalarExpr.of(1.0))
        val b = store.create(position = Vector3(0.0, 0.0, h * 1.5), mass = ScalarExpr.of(1.0))
        groups.add("fluid", a)
        groups.add("fluid", b)
        val fluid = SphFluid(group = "fluid", restDensity = 1.0, gasConstant = 400.0, viscosity = 3.0, smoothingRadius = h)

        assertEquals(Vector3.ZERO, netForceOn(a, store, groups, fluid))
        assertEquals(Vector3.ZERO, netForceOn(b, store, groups, fluid))
    }

    @Test
    fun `two equal-mass particles within the smoothing radius push and drag each other equally and oppositely`() {
        val h = 0.2
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(
            position = Vector3(0.0, 0.0, 0.0), velocity = Vector3(1.0, 0.0, 0.0),
            mass = ScalarExpr.of(1.0),
        )
        val b = store.create(
            position = Vector3(0.0, 0.0, h * 0.5), velocity = Vector3(-0.5, 0.2, 0.0),
            mass = ScalarExpr.of(1.0),
        )
        groups.add("fluid", a)
        groups.add("fluid", b)
        // restDensity well below what these two isolated particles report, so pressure is
        // unambiguously positive (repulsive) rather than clamped to zero - a degenerate case
        // that would make this test pass vacuously.
        val fluid = SphFluid(group = "fluid", restDensity = 1.0, gasConstant = 400.0, viscosity = 3.0, smoothingRadius = h)

        val forceOnA = netForceOn(a, store, groups, fluid)
        val forceOnB = netForceOn(b, store, groups, fluid)

        assertVectorEquals(forceOnA, -forceOnB)
        assertTrue(forceOnA != Vector3.ZERO, "expected a nonzero interaction between two particles well within h")
    }

    @Test
    fun `a lone particle with nothing within its smoothing radius feels no force`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3.ZERO, mass = ScalarExpr.of(1.0))
        groups.add("fluid", a)
        val fluid = SphFluid(group = "fluid", restDensity = 1.0, gasConstant = 400.0, viscosity = 3.0, smoothingRadius = 0.2)

        assertEquals(Vector3.ZERO, netForceOn(a, store, groups, fluid))
    }
}
