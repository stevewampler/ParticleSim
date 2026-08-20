package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §13.1/§13.5: the flag scenario's `dt` was picked from the stability budget, not tuned for
 * looks — this is the empirical check that the margin actually holds. Not an analytic test
 * (wind continuously injects energy, so this isn't a closed system and can't assert energy
 * conservation like [particlesim.physics.EnergyConservationTest] does) — a smoke test that
 * the scenario runs for a real stretch of sim time without blowing up, softly or otherwise.
 */
class FlagStabilityTest {

    @Test
    fun `flag scenario runs for several seconds without blowing up`() {
        val scenario = buildFlag(rows = 8, cols = 14)
        val integrator = Integrator()

        var t = 0.0
        var maxSpeed = 0.0
        val steps = (4.0 / FLAG_DT).toInt() // 4 seconds of sim time

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, FLAG_DT)
            t += FLAG_DT
            for (id in scenario.store.liveIds()) {
                val speed = scenario.store.velocity(id).length()
                if (speed > maxSpeed) maxSpeed = speed
            }
        }

        // Generous headroom over anything a flag should plausibly do — catches "soft" runaway
        // growth that wouldn't trip BlowUpException's NaN/Infinity check.
        assertTrue(maxSpeed < 50.0, "max particle speed $maxSpeed m/s suggests the mesh is unstable at dt=$FLAG_DT")
    }
}
