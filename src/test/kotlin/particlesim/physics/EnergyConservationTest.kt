package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §15.1/§13.5: a closed spring-only system without damping should conserve energy within a
 * bounded tolerance, not drift monotonically — this promotes the runtime diagnostic
 * (§11/§13.5) from logged to asserted.
 */
class EnergyConservationTest {

    @Test
    fun `undamped spring system conserves total mechanical energy`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(-1.0, 0.0, 0.0), mass = particlesim.core.ScalarExpr.of(1.0))
        val b = store.create(position = Vector3(1.5, 0.0, 0.0), mass = particlesim.core.ScalarExpr.of(1.0))
        groups.add("all", a)
        groups.add("all", b)

        val spring = Spring(a, b, restLength = 1.0, stiffness = 40.0)
        val integrator = Integrator()

        fun totalEnergy(): Double =
            Diagnostics.kineticEnergy(store, listOf(a, b)) + spring.potentialEnergy(store)

        val initialEnergy = totalEnergy()
        var t = 0.0
        val dt = 1e-4
        var maxRelativeDrift = 0.0
        repeat(50_000) {
            integrator.step(store, groups, listOf(spring), emptyList(), t, dt)
            t += dt
            val drift = abs(totalEnergy() - initialEnergy) / initialEnergy
            if (drift > maxRelativeDrift) maxRelativeDrift = drift
        }

        assertTrue(maxRelativeDrift < 0.01, "energy drifted by $maxRelativeDrift relative to initial $initialEnergy")
    }
}
