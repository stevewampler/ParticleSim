package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §15.1: N-body gravity between two particles — orbital radius should stay stable over many
 * periods (an integrator-drift regression check as much as a physics check). Mass ratio is
 * large enough that the heavy particle's own drift is negligible, so a simple `v =
 * sqrt(G·M/r)` circular-orbit velocity for the light one is a valid setup.
 */
class TwoBodyOrbitTest {

    @Test
    fun `orbital radius stays stable over many periods`() {
        val g = 1.0
        val heavyMass = 1.0e6
        val lightMass = 1.0
        val r = 10.0
        val v = sqrt(g * heavyMass / r)
        val period = 2.0 * PI * r / v
        val dt = 1e-4

        val store = ParticleStore()
        val groups = Groups()
        val heavy = store.create(position = Vector3.ZERO, mass = particlesim.core.ScalarExpr.of(heavyMass))
        val light = store.create(position = Vector3(r, 0.0, 0.0), velocity = Vector3(0.0, v, 0.0), mass = particlesim.core.ScalarExpr.of(lightMass))
        groups.add("bodies", heavy)
        groups.add("bodies", light)

        val gravity = NBodyGravity("bodies", g = g, softening = 1e-6)
        val integrator = Integrator()

        val periods = 20.0
        var t = 0.0
        var minDist = Double.MAX_VALUE
        var maxDist = 0.0
        val totalSteps = (periods * period / dt).toInt()
        for (step in 0 until totalSteps) {
            integrator.step(store, groups, listOf(gravity), emptyList(), t, dt)
            t += dt
            val dist = (store.position(light) - store.position(heavy)).length()
            minDist = minOf(minDist, dist)
            maxDist = maxOf(maxDist, dist)
        }

        assertTrue(abs(minDist - r) / r < 0.02, "min separation drifted: $minDist vs $r")
        assertTrue(abs(maxDist - r) / r < 0.02, "max separation drifted: $maxDist vs $r")

        // A stalled or wildly precessing orbit could still satisfy the separation bounds
        // above, so confirm it actually completed `periods` full revolutions: after an
        // integer number of periods the light particle should be back near its start.
        val finalPosition = store.position(light) - store.position(heavy)
        val startPosition = Vector3(r, 0.0, 0.0)
        assertTrue(
            (finalPosition - startPosition).length() / r < 0.05,
            "expected to complete $periods full orbits and return near $startPosition, ended at $finalPosition",
        )
    }
}
