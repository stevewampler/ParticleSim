package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for §14's spark fountain over a longer stretch of sim time than the golden
 * file samples: the population should stay bounded at the emitter's cap, destruction should
 * actually be happening (not just accumulating forever), and nothing should blow up.
 */
class SparksStabilityTest {

    @Test
    fun `spark fountain runs for a long stretch without unbounded growth or blow-up`() {
        val scenario = buildSparks(masterSeed = 2L)
        val integrator = Integrator()

        var t = 0.0
        var maxAlive = 0
        var nextId = 0
        val steps = (10.0 / SPARKS_DT).toInt() // 10 seconds of sim time

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, SPARKS_DT)
            scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, SPARKS_DT)
            scenario.emitter.update(scenario.store, scenario.groups, t, SPARKS_DT)
            t += SPARKS_DT
            maxAlive = maxOf(maxAlive, scenario.store.liveIds().size)
        }
        nextId = scenario.store.liveIds().maxOrNull()?.plus(1) ?: 0

        assertTrue(maxAlive <= scenario.emitter.maxAlive, "live count $maxAlive exceeded the emitter's cap of ${scenario.emitter.maxAlive}")
        assertTrue(
            nextId > scenario.store.size,
            "expected far more particles to have ever been spawned ($nextId ids issued) than are " +
                "currently alive (${scenario.store.size}) — destruction doesn't seem to be happening",
        )
    }
}
