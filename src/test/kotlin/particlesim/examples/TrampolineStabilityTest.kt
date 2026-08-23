package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/** §13.1/§13.5: the trampoline's stiffer-than-flag springs and smaller `dt` were picked from
 * the stability budget (see [TRAMPOLINE_DT]'s own doc comment), not tuned for looks - this is
 * the empirical check that the margin actually holds, mirroring [FlagStabilityTest]. */
class TrampolineStabilityTest {

    @Test
    fun `trampoline scenario runs for several seconds without blowing up`() {
        val scenario = buildTrampoline()
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (4.0 / TRAMPOLINE_DT).toInt() // 4 seconds of sim time

        repeat(steps) {
            val stepResult = integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAMPOLINE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAMPOLINE_DT)
            t += TRAMPOLINE_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the mesh is unstable at dt=$TRAMPOLINE_DT")
    }
}
