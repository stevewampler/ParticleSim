package particlesim.examples

import particlesim.physics.Integrator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** [particlesim.physics.TwoBodyOrbitTest] (§15.1) already proves `NBodyGravity` itself holds a
 * stable orbit analytically, for a single planet around a fixed-in-practice star and a `g`/
 * mass/radius/`dt` combination picked purely for that test's own 20-period run. This is the
 * companion empirical check for the actual demo scenario's own parameters — three mutually-
 * interacting planets, not one, and a `dt` picked for a watchable-on-screen period rather than
 * a fast analytic proof (see [ORBITAL_DT]'s own doc comment) — mirroring FlagStabilityTest/
 * TrampolineStabilityTest's "run it for real and check nothing blew up" shape. */
class OrbitalStabilityTest {

    @Test
    fun `orbital scenario runs for many periods with every planet's radius staying bounded`() {
        val scenario = buildOrbital()
        val integrator = Integrator()

        val initialRadii = scenario.planetIds.associateWith {
            (scenario.store.position(it) - scenario.store.position(scenario.starId)).length()
        }

        var t = 0.0
        val steps = (20.0 / ORBITAL_DT).toInt() // 20s - several periods of even the outermost planet

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, ORBITAL_DT)
            t += ORBITAL_DT
        }

        for (id in scenario.planetIds) {
            val starPos = scenario.store.position(scenario.starId)
            val radius = (scenario.store.position(id) - starPos).length()
            val initial = initialRadii.getValue(id)
            assertTrue(
                abs(radius - initial) / initial < 0.15,
                "planet $id's orbital radius drifted from $initial to $radius over 20s",
            )
        }
    }
}
