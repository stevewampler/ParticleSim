package particlesim.debug

import particlesim.collision.SurfaceSelfCollisionRule
import particlesim.collision.SurfaceSelfCollisionSystem
import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §12.4/§13.1/§13.5: a separate test from [particlesim.examples.FlagStabilityTest] (which
 * documents the no-self-collision baseline and shouldn't itself change) - this is the same
 * empirical stability smoke test, but with [FlagScene]'s own self-collision rule
 * (thickness/excludeRings) wired in, mirroring [particlesim.examples.TrampolineStabilityTest]'s
 * pattern of exercising the real collision system, not just forces/constraints.
 */
class FlagSelfCollisionStabilityTest {

    @Test
    fun `flag scenario with self-collision runs for several seconds without blowing up`() {
        val scenario = buildFlag(rows = 8, cols = 14)
        val selfCollisions = SurfaceSelfCollisionSystem(
            listOf(SurfaceSelfCollisionRule(surface = scenario.surface, thickness = 0.05, excludeRings = 2)),
        )
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (4.0 / FLAG_DT).toInt() // 4 seconds of sim time

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, FLAG_DT)
            selfCollisions.resolve(scenario.store)
            t += FLAG_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests self-collision destabilizes the mesh at dt=$FLAG_DT")
    }
}
