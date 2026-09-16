package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for the campfire over a longer stretch of sim time than any golden-file sample
 * would cover: the flame should never fully die out once warmed up, its population should stay
 * well within the emitter's cap (which this scenario's rate/lifetime never actually reaches -
 * see `buildFire`'s own doc comment), destruction should actually be happening, and nothing
 * should blow up.
 */
class FireStabilityTest {

    @Test
    fun `campfire runs for a long stretch without dying out, unbounded growth, or blow-up`() {
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
        assertTrue(minAliveAfterWarmup > 0, "expected a continuously-emitting fire to never fully die out once warmed up")
        assertTrue(
            nextId > scenario.store.size,
            "expected far more particles to have ever been spawned ($nextId ids issued) than are " +
                "currently alive (${scenario.store.size}) - destruction doesn't seem to be happening",
        )
    }
}
