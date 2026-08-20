package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §5.4: breakable springs/dampers, asymmetric thresholds, end-of-step batch removal. */
class BreakableForceTest {

    @Test
    fun `spring breaks when stretched beyond extensionBreakThreshold`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0, breakThreshold = 0.5)

        assertFalse(spring.shouldBreak(store))
        store.setPosition(b, Vector3(2.0, 0.0, 0.0)) // displacement = 1.0 > 0.5
        assertTrue(spring.shouldBreak(store))
    }

    @Test
    fun `unbounded compression threshold never breaks under compression`() {
        // rope-like: goes slack under compression instead of breaking
        val store = ParticleStore()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(0.01, 0.0, 0.0)) // heavily compressed
        val spring = Spring(
            a, b, restLength = 1.0, stiffness = 1.0, compressionStiffness = 0.0,
            extensionBreakThreshold = 0.5, // compressionBreakThreshold left at its infinite default
        )

        assertFalse(spring.shouldBreak(store))
    }

    @Test
    fun `damper breaks on relative-velocity magnitude, not distance`() {
        val store = ParticleStore()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0), velocity = Vector3(5.0, 0.0, 0.0))
        val damper = Damper(a, b, damping = 1.0, breakThreshold = 3.0)

        assertTrue(damper.shouldBreak(store))
    }

    @Test
    fun `a broken spring still applies its force the step it broke, but not the next`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(3.0, 0.0, 0.0)) // already past threshold
        var forces = listOf<Force>(Spring(a, b, restLength = 1.0, stiffness = 100.0, breakThreshold = 1.0))
        val integrator = Integrator()

        val result = integrator.step(store, groups, forces, emptyList(), 0.0, 0.001)
        assertEquals(1, result.brokenForces.size)
        // This step's velocity update did happen (force applied before removal takes effect).
        assertTrue(store.velocity(b).x < 0.0, "spring should have pulled b toward a this step")

        forces = forces - result.brokenForces
        val velocityAfterBreak = store.velocity(b)
        integrator.step(store, groups, forces, emptyList(), 0.001, 0.001)
        assertEquals(velocityAfterBreak, store.velocity(b), "no force left to change velocity once removed")
    }

    @Test
    fun `multiple connections exceeding threshold in the same step all break together`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(3.0, 0.0, 0.0))
        val c = store.create(position = Vector3(6.0, 0.0, 0.0))
        val spring1 = Spring(a, b, restLength = 1.0, stiffness = 10.0, breakThreshold = 1.0)
        val spring2 = Spring(b, c, restLength = 1.0, stiffness = 10.0, breakThreshold = 1.0)
        val integrator = Integrator()

        val result = integrator.step(store, groups, listOf(spring1, spring2), emptyList(), 0.0, 0.001)

        assertEquals(2, result.brokenForces.size)
        assertTrue(spring1 in result.brokenForces)
        assertTrue(spring2 in result.brokenForces)
    }
}
