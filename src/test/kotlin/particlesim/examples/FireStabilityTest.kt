package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for the campfire over a longer stretch of sim time than any golden-file sample
 * would cover: this scenario is tuned dense enough (see `buildFire`'s own doc comment) that the
 * population should spend real time pinned at the emitter's cap once warmed up, not just
 * fluctuate somewhere below it, destruction should actually be happening (eviction and/or
 * lifetime expiry), and nothing should blow up.
 */
class FireStabilityTest {

    @Test
    fun `campfire runs for a long stretch, stays pinned near its cap, and never blows up`() {
        val scenario = buildFire(masterSeed = 2L)
        val integrator = Integrator()

        var t = 0.0
        var maxAlive = 0
        var minAliveAfterWarmup = Int.MAX_VALUE
        val steps = (10.0 / FIRE_DT).toInt() // 10 seconds of sim time
        val warmupSteps = (1.0 / FIRE_DT).toInt() // let the population reach equilibrium first

        repeat(steps) { i ->
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, FIRE_DT)
            scenario.destruction.resolve(scenario.store, scenario.groups, scenario.forces, t, FIRE_DT)
            scenario.emitter.update(scenario.store, scenario.groups, t, FIRE_DT)
            t += FIRE_DT
            val alive = scenario.store.liveIds().size
            maxAlive = maxOf(maxAlive, alive)
            if (i >= warmupSteps) minAliveAfterWarmup = minOf(minAliveAfterWarmup, alive)
        }
        val nextId = scenario.store.liveIds().maxOrNull()?.plus(1) ?: 0

        assertTrue(maxAlive <= scenario.emitter.maxAlive, "live count $maxAlive exceeded the emitter's cap of ${scenario.emitter.maxAlive}")
        assertTrue(
            minAliveAfterWarmup >= scenario.emitter.maxAlive / 2,
            "expected this dense a fire to stay reasonably close to its cap of ${scenario.emitter.maxAlive} " +
                "once warmed up, but it dropped to $minAliveAfterWarmup",
        )
        assertTrue(
            nextId > scenario.store.size,
            "expected far more particles to have ever been spawned ($nextId ids issued) than are " +
                "currently alive (${scenario.store.size}) - destruction doesn't seem to be happening",
        )
    }
}
