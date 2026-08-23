package particlesim.examples

import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §12.8's actual deliverable isn't "a restitution coefficient," it's a ball that visibly
 * launches back up because the trampoline's surface deformed and sprang back - the one thing
 * this worked example specifically claims over reusing [buildBallBounce]'s static floor. This
 * test is the closest thing to an end-to-end check of that claim: drop the ball, find how deep
 * it pushed into the mat, then confirm it rises substantially above that depth afterward -
 * something a broken [particlesim.collision.SurfaceCollisionSystem] wiring (e.g. contact
 * detected but no reaction applied, or a rule that silently no-ops) could still pass every
 * isolated component test while failing here.
 */
class TrampolineBounceTest {

    @Test
    fun `a dropped ball rebounds well above its deepest penetration into the mat`() {
        val scenario = buildTrampoline(dropHeight = 1.0)
        val integrator = Integrator()

        var t = 0.0
        val steps = (2.5 / TRAMPOLINE_DT).toInt()
        var minY = Double.POSITIVE_INFINITY
        var minStep = -1
        val heights = DoubleArray(steps)

        repeat(steps) { i ->
            integrator.step(scenario.store, scenario.groups, scenario.forces, scenario.constraints, t, TRAMPOLINE_DT)
            scenario.collisions.resolve(scenario.store, scenario.groups, t, TRAMPOLINE_DT)
            t += TRAMPOLINE_DT
            val y = scenario.store.position(scenario.ballId).y
            heights[i] = y
            if (y < minY) {
                minY = y
                minStep = i
            }
        }

        val apexAfterContact = heights.drop(minStep + 1).maxOrNull() ?: 0.0

        assertTrue(minY < 0.1, "ball should have pushed noticeably into the mat (deepest y=$minY)")
        assertTrue(
            apexAfterContact - minY > 0.15,
            "ball should rebound well above its deepest penetration: min=$minY, apex after=$apexAfterContact",
        )
    }
}
