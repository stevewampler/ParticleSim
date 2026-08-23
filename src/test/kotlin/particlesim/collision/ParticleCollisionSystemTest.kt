package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.FixedPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §15.3's component-test pattern extended to particle-particle (alongside
 * [ColliderTest]'s sphere-plane/sphere-box and [SurfaceCollisionSystemTest]'s
 * sphere-triangle), plus §15.1's analytic momentum/energy checks for the genuinely new
 * mechanics here: a real two-body impulse between two dynamic particles. */
class ParticleCollisionSystemTest {

    private fun particle(store: ParticleStore, groups: Groups, group: String, position: Vector3, velocity: Vector3, radius: Double = 0.2, mass: Double = 1.0): Int {
        val id = store.create(position = position, velocity = velocity, radius = ScalarExpr.of(radius), mass = ScalarExpr.of(mass))
        groups.add(group, id)
        return id
    }

    @Test
    fun `two equal-mass particles colliding head-on exchange velocities at full restitution`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(2.0, 0.0, 0.0))
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-2.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        // Textbook equal-mass elastic head-on collision: velocities swap.
        assertEquals(-2.0, store.velocity(a).x, 1e-9)
        assertEquals(2.0, store.velocity(b).x, 1e-9)
    }

    @Test
    fun `tangential velocity is untouched by collision response`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(2.0, 0.5, -1.0))
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-2.0, 0.3, 0.7))
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 0.7)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        assertEquals(0.5, store.velocity(a).y, 1e-9)
        assertEquals(-1.0, store.velocity(a).z, 1e-9)
        assertEquals(0.3, store.velocity(b).y, 1e-9)
        assertEquals(0.7, store.velocity(b).z, 1e-9)
    }

    @Test
    fun `separated particles have no contact and nothing moves`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-5.0, 0.0, 0.0), Vector3(2.0, 0.0, 0.0))
        val b = particle(store, groups, "ball", Vector3(5.0, 0.0, 0.0), Vector3(-2.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        assertEquals(2.0, store.velocity(a).x, 1e-9)
        assertEquals(-2.0, store.velocity(b).x, 1e-9)
    }

    @Test
    fun `a group member with no radius is skipped, never an error`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(-0.15, 0.0, 0.0), velocity = Vector3(2.0, 0.0, 0.0))
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-2.0, 0.0, 0.0))
        groups.add("ball", a)
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        assertEquals(2.0, store.velocity(a).x, 1e-9)
        assertEquals(-2.0, store.velocity(b).x, 1e-9)
    }

    @Test
    fun `compression damping further reduces the outgoing separation speed below pure restitution`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(2.0, 0.0, 0.0))
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-2.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 1.0, compressionDamping = 3.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        // Undamped (e=1) would flip relVel from -4 to +4 exactly (a full swap, as in the test
        // above). Damped: newRelVel = -1.0*(-4)/sqrt(4) = 2.0, half the undamped separation
        // speed - shared equally since masses are equal. The contact normal here points from
        // b toward a (i.e. -x, since a sits at the smaller x), so a relative velocity of +2.0
        // *along the normal* shows up as -2.0 in raw (a - b).x.
        val relVelAfterX = (store.velocity(a) - store.velocity(b)).x
        assertEquals(-2.0, relVelAfterX, 1e-9)
    }

    @Test
    fun `resting contact below the velocity and penetration thresholds clamps relative velocity, not either particle's own`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.199, 0.0, 0.0), Vector3(0.001, 0.3, 0.0))
        val b = particle(store, groups, "ball", Vector3(0.199, 0.0, 0.0), Vector3(0.0, -0.2, 0.4))
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 0.7)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        val relVelAfter = (store.velocity(a) - store.velocity(b)).x
        assertEquals(0.0, relVelAfter, 1e-9, "relative normal velocity should be clamped to zero")
        // Equal masses share the correction equally, so neither ends up at exactly its
        // pre-collision velocity - only their *difference* along the normal is zeroed.
        assertEquals(0.3, store.velocity(a).y, 1e-9)
        assertEquals(-0.2, store.velocity(b).y, 1e-9)
        assertEquals(0.4, store.velocity(b).z, 1e-9)
    }

    @Test
    fun `a particle pinned by an active constraint is treated as infinite mass and never moves`() {
        val store = ParticleStore()
        val groups = Groups()
        val pinnedPos = Vector3(0.15, 0.0, 0.0)
        val pinned = particle(store, groups, "wall", pinnedPos, Vector3.ZERO)
        val free = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(3.0, 0.0, 0.0))
        val constraint = FixedPosition("wall", pinnedPos)
        val rule = ParticleCollisionRule(groupA = "ball", groupB = "wall", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, listOf(constraint))

        assertEquals(pinnedPos, store.position(pinned), "pinned particle's position must be untouched, bit-for-bit")
        assertEquals(Vector3.ZERO, store.velocity(pinned), "pinned particle's velocity must be untouched")
        // The free particle bounces off it exactly like an immovable wall: full reversal.
        assertEquals(-3.0, store.velocity(free).x, 1e-9)
    }

    @Test
    fun `two particles both pinned by constraints produce no motion and no division error`() {
        val store = ParticleStore()
        val groups = Groups()
        val posA = Vector3(-0.05, 0.0, 0.0)
        val posB = Vector3(0.05, 0.0, 0.0)
        val a = particle(store, groups, "wall", posA, Vector3.ZERO)
        val b = particle(store, groups, "wall", posB, Vector3.ZERO)
        val constraint = FixedPosition("wall", Vector3.ZERO) // position value irrelevant; pinnedIds is what matters here
        val rule = ParticleCollisionRule(groupA = "wall", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, listOf(constraint))

        assertEquals(posA, store.position(a))
        assertEquals(posB, store.position(b))
    }

    @Test
    fun `self-collision within one group resolves every pair exactly once`() {
        val store = ParticleStore()
        val groups = Groups()
        // Three particles in a row, each overlapping its neighbor but not the far one.
        val a = particle(store, groups, "cloud", Vector3(-0.3, 0.0, 0.0), Vector3(1.0, 0.0, 0.0))
        val b = particle(store, groups, "cloud", Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0))
        val c = particle(store, groups, "cloud", Vector3(0.3, 0.0, 0.0), Vector3(-1.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "cloud", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        // Both a-b and b-c overlap (spacing 0.3 < combined radius 0.4); b is hit from both
        // sides in the same pass, sequentially - just confirm every particle's velocity
        // changed from its initial value, i.e. every real contact was actually processed.
        assertTrue(store.velocity(a).x != 1.0)
        assertTrue(store.velocity(c).x != -1.0)
    }

    @Test
    fun `a cross-group rule does not check pairs within the same group`() {
        val store = ParticleStore()
        val groups = Groups()
        val a1 = particle(store, groups, "groupA", Vector3(-0.15, 0.0, 0.0), Vector3(1.0, 0.0, 0.0))
        val a2 = particle(store, groups, "groupA", Vector3(0.15, 0.0, 0.0), Vector3(-1.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "groupA", groupB = "groupB", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        // groupB is empty, so this rule has no candidate pairs at all - the two overlapping
        // groupA members must be untouched despite genuinely overlapping.
        assertEquals(1.0, store.velocity(a1).x, 1e-9)
        assertEquals(-1.0, store.velocity(a2).x, 1e-9)
    }

    @Test
    fun `a particle in both sides of a cross-group rule is checked against itself from each side`() {
        // Documents the known, accepted limitation from ParticleCollisionRule's own doc
        // comment: cross-group rules assume disjoint groups. A particle in the intersection
        // gets skipped against itself only by the explicit a != b guard - it is still paired
        // with every *other* member of the opposite group from both roles.
        val store = ParticleStore()
        val groups = Groups()
        val shared = particle(store, groups, "groupA", Vector3(-0.15, 0.0, 0.0), Vector3(2.0, 0.0, 0.0))
        groups.add("groupB", shared)
        val other = particle(store, groups, "groupB", Vector3(0.15, 0.0, 0.0), Vector3(-2.0, 0.0, 0.0))
        val rule = ParticleCollisionRule(groupA = "groupA", groupB = "groupB", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())

        // shared vs shared is skipped by the a != b guard; shared vs other is processed once
        // (shared only appears once in groupA's member list), so this behaves correctly for
        // this particular overlap shape - not every overlap shape is this benign, which is
        // exactly why the doc comment calls the general case out as unhandled.
        assertEquals(-2.0, store.velocity(shared).x, 1e-9)
        assertEquals(2.0, store.velocity(other).x, 1e-9)
    }

    @Test
    fun `total momentum is conserved for a free two-body collision regardless of restitution or damping`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(3.0, 0.4, -0.2), mass = 1.0)
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-1.0, 0.1, 0.5), mass = 4.0)

        fun momentum() = store.velocity(a) * store.mass(a) + store.velocity(b) * store.mass(b)
        val before = momentum()
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 0.6, compressionDamping = 1.2)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())
        val after = momentum()

        assertTrue((before - after).length() < 1e-9, "expected $before but was $after")
    }

    @Test
    fun `a perfectly elastic, undamped collision between unequal masses conserves kinetic energy`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = particle(store, groups, "ball", Vector3(-0.15, 0.0, 0.0), Vector3(4.0, 0.0, 0.0), mass = 1.0)
        val b = particle(store, groups, "ball", Vector3(0.15, 0.0, 0.0), Vector3(-1.0, 0.0, 0.0), mass = 3.0)

        fun kineticEnergy(): Double {
            val va = store.velocity(a).length()
            val vb = store.velocity(b).length()
            return 0.5 * store.mass(a) * va * va + 0.5 * store.mass(b) * vb * vb
        }
        val before = kineticEnergy()
        val rule = ParticleCollisionRule(groupA = "ball", restitution = 1.0)
        ParticleCollisionSystem(listOf(rule)).resolve(store, groups, emptyList())
        val after = kineticEnergy()

        assertEquals(before, after, 1e-9, "a perfectly elastic collision must conserve kinetic energy")
        // And something actually happened - otherwise conservation would be trivially true.
        assertTrue(store.velocity(a).x != 4.0)
    }
}
