package particlesim.examples

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §4.5's shape library: proves that two shape instances (two flags, or a flag and a
 * ball-bounce) can actually share one scene — distinct ids, distinct namespaced groups, and
 * each one physically unaffected by the other's presence — not just that the new parameters
 * compile. */
class ShapeCompositionTest {

    @Test
    fun `two flags in one shared store get distinct ids and distinct namespaced groups`() {
        val store = ParticleStore()
        val groups = Groups()

        val flag1 = buildFlag(rows = 2, cols = 2, store = store, groups = groups, placement = ShapePlacement(instanceName = "flag1"))
        val flag2 = buildFlag(
            rows = 2, cols = 2, store = store, groups = groups,
            placement = ShapePlacement(offset = Vector3(10.0, 0.0, 0.0), instanceName = "flag2"),
        )

        // Same shared store -> no id collisions between the two instances.
        val flag1Ids = flag1.grid.flatten().toSet()
        val flag2Ids = flag2.grid.flatten().toSet()
        assertTrue(flag1Ids.intersect(flag2Ids).isEmpty(), "the two flags' particle ids must not overlap")
        assertEquals(8, store.size, "both flags' particles (2x2 each) should be live in the one shared store")

        // Namespaced groups: each instance's own group is separate, and only contains its own ids.
        assertEquals(flag1Ids, groups.membersOf("flag1.cloth"))
        assertEquals(flag2Ids, groups.membersOf("flag2.cloth"))

        // The offset actually applied: flag2's particles are all +10 in x relative to flag1's.
        val flag1Origin = store.position(flag1.grid[0][0])
        val flag2Origin = store.position(flag2.grid[0][0])
        assertEquals(flag1Origin + Vector3(10.0, 0.0, 0.0), flag2Origin)
    }

    @Test
    fun `a flag and a ball-bounce coexist and step correctly without interfering`() {
        val store = ParticleStore()
        val groups = Groups()

        val flag = buildFlag(rows = 2, cols = 2, store = store, groups = groups, placement = ShapePlacement(instanceName = "flag"))
        val ball = buildBallBounce(
            dropHeight = 3.0, store = store, groups = groups,
            placement = ShapePlacement(offset = Vector3(5.0, 0.0, 0.0), instanceName = "ball"),
        )

        assertEquals(setOf("flag.cloth", "flag.pole"), groups.groupsOf(flag.grid[0][0]) + groups.groupsOf(flag.grid[0][1]))
        assertEquals(setOf("ball.ball"), groups.groupsOf(ball.ballId))

        // Step both scenarios' forces/constraints together, as one combined scene would.
        val allForces = flag.forces + ball.forces
        val allConstraints = flag.constraints
        val integrator = Integrator()
        var t = 0.0
        val dt = 1e-3
        val initialBallY = store.position(ball.ballId).y
        repeat(100) {
            integrator.step(store, groups, allForces, allConstraints, t, dt)
            ball.collisions.resolve(store, groups, t, dt)
            t += dt
        }

        // The ball fell (gravity acted on it, unaffected by the flag's own forces/groups).
        assertTrue(store.position(ball.ballId).y < initialBallY, "the ball should have fallen under its own gravity")
        // The flag's pole particle stayed exactly fixed (unaffected by the ball's collision/gravity).
        assertEquals(store.position(flag.grid[0][0]), Vector3(0.0, 0.0, 0.0))
        // The ball never drifted in x/z (gravity is purely -y) — confirms no cross-talk between
        // the two shapes' forces despite sharing one force/constraint list.
        assertEquals(5.0, store.position(ball.ballId).x, 1e-12)
        assertEquals(0.0, store.position(ball.ballId).z, 1e-12)
    }
}
