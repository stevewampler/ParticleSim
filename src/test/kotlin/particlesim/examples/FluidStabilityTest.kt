package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A smoke test for the SPH fluid over a stretch of sim time no golden-file sample would cover:
 * nothing should blow up (NaN/Infinity, or an escape from the container by more than a small
 * numerical-correction slop), and settling under gravity should actually reduce the block's
 * kinetic energy rather than leaving it sloshing indefinitely.
 */
class FluidStabilityTest {

    @Test
    fun `fluid block settles under gravity without blowing up or escaping its container`() {
        val scenario = buildFluid()
        val integrator = Integrator()
        val ids = scenario.store.liveIds()

        var t = 0.0
        val steps = (2.0 / FLUID_DT).toInt() // 2 seconds of sim time

        repeat(steps) {
            integrator.step(scenario.store, scenario.groups, scenario.forces, emptyList(), t, FLUID_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, FLUID_DT)
            t += FLUID_DT

            for (id in ids) {
                val pos = scenario.store.position(id)
                val vel = scenario.store.velocity(id)
                assertTrue(pos.isFinite() && vel.isFinite(), "particle $id has a non-finite position/velocity at t=$t: pos=$pos vel=$vel")
                assertTrue(
                    pos.x in -0.7..0.7 && pos.z in -0.7..0.7 && pos.y in -0.1..3.0,
                    "particle $id escaped the container at t=$t: pos=$pos",
                )
            }
        }

        val finalKineticEnergy = ids.sumOf { id ->
            val v = scenario.store.velocity(id)
            0.5 * scenario.store.mass(id) * v.lengthSquared()
        }
        val averageSpeed = ids.sumOf { id -> scenario.store.velocity(id).length() } / ids.size
        assertTrue(
            averageSpeed < 1.0,
            "expected the block to have settled to a low average speed after 2s, got $averageSpeed " +
                "(total kinetic energy $finalKineticEnergy)",
        )
    }
}
