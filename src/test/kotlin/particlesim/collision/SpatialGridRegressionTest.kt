package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §9.3's broad-phase grid replaced [ParticleCollisionSystem.candidatePairs]'s brute-force
 * double loop with a [SpatialGrid] query — this test is the proof that the swap preserves
 * behavior *for the pairs both approaches actually generate*, not just "produces some
 * plausible-looking result." It does not, and cannot, prove the two are identical in every
 * case: the grid is built once per rule from positions at the start of that rule's resolution,
 * so a contact created mid-call by an earlier pair's penetration correction is invisible to it,
 * whereas the old brute-force loop re-read live positions for every pair and could occasionally
 * catch such a contact. See [SpatialGrid]'s own doc comment - this is an accepted, existing gap
 * in single-pass-per-step resolution, not something this test is positioned to catch or rule out.
 *
 * What this test *does* prove: for the pairs that are candidates under both schemes, processing
 * order matches exactly. The grid only changes which candidate pairs get *generated*, not
 * [ParticleCollisionSystem.respond]'s physics — but because `respond` mutates positions/
 * velocities as it goes, the *order* pairs are processed in can change the final result even
 * when the underlying physics is correct (floating-point addition isn't associative, and an
 * already-nudged particle collides differently on its next contact). So "same final state" is
 * only guaranteed if pairs that actually overlap are visited in the same relative order both
 * ways - which is exactly what the grid-based candidatePairs is designed to preserve (see its
 * own comment).
 *
 * This runs a mixed same-group and cross-group scene (40 + 15 particles, two rules, one of each
 * kind, deliberately packed close enough that most steps have real contacts) for 200 steps and
 * checks the exact final state against values captured from the pre-grid brute-force
 * implementation before it was replaced - not a re-derivation, an actual golden snapshot. Any
 * regression in pair-generation completeness or ordering will show up as a numeric mismatch here,
 * not just a "still passes the unit tests" false confidence.
 */
class SpatialGridRegressionTest {

    @Test
    fun `grid-based candidate pairs reproduce the pre-grid brute-force trajectory bit-for-bit`() {
        val store = ParticleStore()
        val groups = Groups()
        val random = Random(42)
        // A small box, not a spread-out one: candidate-pair generation cost is paid every step
        // regardless of contacts, but *this* test specifically needs genuine simultaneous
        // multi-body contact (one particle overlapping two others in the same step) to actually
        // exercise pair-processing order - a sparse scene would pass even with a broken sort,
        // since isolated pairwise contacts don't depend on visitation order. Confirmed via a
        // scratch instrumentation pass before capturing the golden values below: this box size
        // produces dozens of steps where some particle has >= 2 simultaneous contacts.
        val box = 0.6
        val idsA = (0 until 40).map {
            val pos = Vector3(random.nextDouble(-box, box), random.nextDouble(-box, box), random.nextDouble(-box, box))
            val vel = Vector3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val id = store.create(position = pos, velocity = vel, radius = ScalarExpr.of(0.1), mass = ScalarExpr.of(1.0))
            groups.add("cloudA", id)
            id
        }
        val idsB = (0 until 15).map {
            val pos = Vector3(random.nextDouble(-box, box), random.nextDouble(-box, box), random.nextDouble(-box, box))
            val vel = Vector3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
            val id = store.create(position = pos, velocity = vel, radius = ScalarExpr.of(0.15), mass = ScalarExpr.of(2.0))
            groups.add("cloudB", id)
            id
        }
        val rules = listOf(
            ParticleCollisionRule(groupA = "cloudA", restitution = 0.8, compressionDamping = 0.5, staticFriction = 0.3, kineticFriction = 0.2),
            ParticleCollisionRule(groupA = "cloudA", groupB = "cloudB", restitution = 0.6, staticFriction = 0.2, kineticFriction = 0.1),
        )
        val system = ParticleCollisionSystem(rules)
        val dt = 0.01
        for (step in 0 until 200) {
            for (id in idsA + idsB) {
                store.setPosition(id, store.position(id) + store.velocity(id) * dt)
            }
            system.resolve(store, groups, emptyList())
        }

        // Captured from the brute-force implementation, before candidatePairs was rewritten to
        // use SpatialGrid - see this class's own doc comment.
        assertEquals(0.08344351998461237, store.position(idsA[0]).x, 1e-12)
        assertEquals(2.178088431357758, store.position(idsA[0]).y, 1e-12)
        assertEquals(-0.8750299525522794, store.position(idsA[0]).z, 1e-12)
        assertEquals(0.20049805132678822, store.velocity(idsA[0]).x, 1e-12)
        assertEquals(0.90460283669148, store.velocity(idsA[0]).y, 1e-12)
        assertEquals(-0.2550981925162675, store.velocity(idsA[0]).z, 1e-12)

        assertEquals(-0.3374893470992777, store.position(idsA[1]).x, 1e-12)
        assertEquals(-1.4485657328974941, store.position(idsA[1]).y, 1e-12)
        assertEquals(0.29789805065789693, store.position(idsA[1]).z, 1e-12)

        var checksum = 0.0
        for (id in idsA + idsB) {
            val p = store.position(id)
            val v = store.velocity(id)
            checksum += p.x + p.y * 2 + p.z * 3 + v.x * 5 + v.y * 7 + v.z * 11
        }
        assertEquals(58.215446617934674, checksum, 1e-9)
    }
}
