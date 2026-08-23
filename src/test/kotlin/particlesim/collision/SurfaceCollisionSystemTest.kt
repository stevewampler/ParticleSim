package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.surface.Surface
import particlesim.surface.Triangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §15.3's component-test pattern (extending [ColliderTest]'s sphere-plane/sphere-box coverage
 * to sphere-vs-triangulated-surface, per CLAUDE.md's rule that a new collision behavior needs
 * one) plus a momentum-conservation analytic check (§15.1) for the genuinely new mechanics
 * here: the reaction impulse applied back onto the surface's vertices. */
class SurfaceCollisionSystemTest {

    private fun flatTriangle(store: ParticleStore, groups: Groups, mass: Double = 1.0): Triangle {
        val a = store.create(position = Vector3(-1.0, 0.0, -1.0), mass = ScalarExpr.of(mass))
        val b = store.create(position = Vector3(2.0, 0.0, -1.0), mass = ScalarExpr.of(mass))
        val c = store.create(position = Vector3(-1.0, 0.0, 2.0), mass = ScalarExpr.of(mass))
        groups.add("mesh", a); groups.add("mesh", b); groups.add("mesh", c)
        return Triangle(a, b, c)
    }

    private fun ball(store: ParticleStore, groups: Groups, position: Vector3, velocity: Vector3, radius: Double = 0.2, mass: Double = 1.0): Int {
        val id = store.create(position = position, velocity = velocity, radius = ScalarExpr.of(radius), mass = ScalarExpr.of(mass))
        groups.add("ball", id)
        return id
    }

    @Test
    fun `ball resting above the flat interior bounces off, split with the three vertices by mass`() {
        // Unlike ParticleColliderRule's infinite-mass collider, the surface's vertices have
        // finite mass and absorb part of the impulse - the ball does not get the full
        // "-e*relVel" bounce a static floor would give it. This point sits exactly at the
        // triangle's centroid, so barycentric weights are (1/3, 1/3, 1/3): invMassSum =
        // 1/m_ball + (1/9+1/9+1/9)/m_vertex = 1 + 1/3 = 4/3 (all masses 1.0), impulse =
        // deltaRelVel/invMassSum = 8.5/(4/3) = 6.375, so ball vy = -5.0 + 6.375 = 1.375.
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7)
        val system = SurfaceCollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(1.375, store.velocity(ballId).y, 1e-9)
        // Each vertex shares the reaction equally (weight 1/3 each): -impulse*(1/3)/1 each.
        assertEquals(-2.125, store.velocity(triangle.a).y, 1e-9)
        assertEquals(-2.125, store.velocity(triangle.b).y, 1e-9)
        assertEquals(-2.125, store.velocity(triangle.c).y, 1e-9)
    }

    @Test
    fun `ball clear of the surface has no contact and nothing moves`() {
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val ballId = ball(store, groups, position = Vector3(0.0, 5.0, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7)
        val system = SurfaceCollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(-5.0, store.velocity(ballId).y, 1e-9)
        for (id in groups.membersOf("mesh")) assertEquals(Vector3.ZERO, store.velocity(id))
    }

    @Test
    fun `a group member with no radius is skipped, never an error`() {
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val id = store.create(position = Vector3(0.0, 0.1, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        groups.add("ball", id)
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7)
        val system = SurfaceCollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(-5.0, store.velocity(id).y, 1e-9)
    }

    @Test
    fun `resting contact below the velocity and penetration thresholds clamps relative velocity to zero`() {
        // Unlike a static collider (infinite mass), clamping "relative velocity to zero" here
        // settles the ball and the contact point onto a shared common velocity rather than
        // pinning the ball's own velocity to exactly zero - the surface absorbs its share.
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.199, 0.0), velocity = Vector3(0.3, 0.001, 0.4))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7)
        val system = SurfaceCollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        val contactVy = (store.velocity(triangle.a).y + store.velocity(triangle.b).y + store.velocity(triangle.c).y) / 3.0
        assertEquals(contactVy, store.velocity(ballId).y, 1e-9, "relative normal velocity should be clamped to zero")
        assertEquals(0.3, store.velocity(ballId).x, 1e-9)
        assertEquals(0.4, store.velocity(ballId).z, 1e-9)
    }

    @Test
    fun `the deepest-penetrating triangle wins when a surface has more than one`() {
        val store = ParticleStore()
        val groups = Groups()
        // Two coplanar triangles side by side; the ball sits only over the second one.
        val a = store.create(position = Vector3(-1.0, 0.0, -1.0))
        val b = store.create(position = Vector3(0.0, 0.0, -1.0))
        val c = store.create(position = Vector3(-1.0, 0.0, 1.0))
        val d = store.create(position = Vector3(1.0, 0.0, -1.0))
        val e = store.create(position = Vector3(2.0, 0.0, -1.0))
        val f = store.create(position = Vector3(1.0, 0.0, 1.0))
        for (id in listOf(a, b, c, d, e, f)) groups.add("mesh", id)
        val ballId = ball(store, groups, position = Vector3(1.3, 0.15, -0.5), velocity = Vector3(0.0, -5.0, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(Triangle(a, b, c), Triangle(d, e, f))), restitution = 0.7)
        val system = SurfaceCollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertTrue(store.velocity(ballId).y > -5.0, "ball should have bounced, not passed through")
        for (id in listOf(a, b, c)) assertEquals(Vector3.ZERO, store.velocity(id), "first triangle wasn't touched")
        assertTrue(
            listOf(d, e, f).any { store.velocity(it) != Vector3.ZERO },
            "second triangle (the one actually under the ball) should have received the reaction",
        )
    }

    @Test
    fun `total momentum of an unpinned, unforced particle-plus-triangle system is conserved by the impulse`() {
        // Deliberately no gravity, no constraints, nothing pinning the triangle's vertices —
        // an impulse response only conserves momentum for genuinely free bodies. A pinned
        // vertex (e.g. FixedPosition, as the trampoline's rim would use) discards whatever
        // velocity an impulse gives it the very next step, so asserting conservation against a
        // pinned scenario would be asserting something the design never intended to hold.
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups, mass = 2.0)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.5, -5.0, -0.3), mass = 3.0)

        fun totalMomentum(): Vector3 {
            var p = Vector3.ZERO
            for (id in store.liveIds()) p += store.velocity(id) * store.mass(id)
            return p
        }

        val before = totalMomentum()
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7, compressionDamping = 1.5)
        SurfaceCollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)
        val after = totalMomentum()

        assertTrue((before - after).length() < 1e-9, "expected $before but was $after")
        // And the ball's own vertical velocity actually changed substantially - otherwise
        // "conserved" would be trivially true because nothing happened. (It needn't end up
        // positive: with finite, heavier vertex mass and compression damping in the mix, a
        // real collision can still leave the ball moving into the surface, just slower - see
        // the dedicated "split with the three vertices by mass" test for the undamped case
        // where it does reverse.)
        assertTrue(kotlin.math.abs(store.velocity(ballId).y - (-5.0)) > 1.0, "ball's velocity should have changed substantially")
    }

    @Test
    fun `impulse is distributed across vertices in proportion to barycentric weight`() {
        // Directly above vertex a's own position - barycentric weight concentrates entirely on
        // a (u=1, v=w=0), reducing this to a plain two-body restitution collision between the
        // ball and vertex a alone: invMassSum = 1/1 + 1^2/1 = 2, impulse = 8.5/2 = 4.25, so
        // ball vy = -5.0+4.25 = -0.75 and vertex a's vy = 0 - 4.25*1/1 = -4.25. The ball still
        // ends up moving downward overall - expected, since it collided with a free mass equal
        // to its own rather than an immovable floor.
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups, mass = 1.0)
        val ballId = ball(store, groups, position = Vector3(-1.0, 0.15, -1.0), velocity = Vector3(0.0, -5.0, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7)
        SurfaceCollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(-4.25, store.velocity(triangle.a).y, 1e-9)
        assertEquals(0.0, store.velocity(triangle.b).y, 1e-9)
        assertEquals(0.0, store.velocity(triangle.c).y, 1e-9)
        assertEquals(-0.75, store.velocity(ballId).y, 1e-9)
    }

    // --- Friction (§12.5) --------------------------------------------------------------------
    // Same centroid contact as "ball resting above the flat interior bounces off..." above
    // (u=v=w=1/3), so the normal-direction numbers here (vy=1.375 for the ball, -2.125 for
    // each vertex) are already-verified cross-checks that friction leaves the normal response
    // untouched - only the tangential (x) component is new.

    @Test
    fun `kinetic friction partially decelerates tangential velocity, split across vertices by barycentric weight`() {
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(1.0, -5.0, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7, kineticFriction = 0.1)
        SurfaceCollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.3625, store.velocity(ballId).x, 1e-9)
        assertEquals(1.375, store.velocity(ballId).y, 1e-9, "normal response unaffected by friction")
        for (id in listOf(triangle.a, triangle.b, triangle.c)) {
            assertEquals(0.2125, store.velocity(id).x, 1e-9)
            assertEquals(-2.125, store.velocity(id).y, 1e-9, "normal response unaffected by friction")
        }
    }

    @Test
    fun `static friction fractionally arrests a resting contact's tangential velocity`() {
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.199, 0.0), velocity = Vector3(0.3, 0.001, 0.0))
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7, staticFriction = 0.4)
        SurfaceCollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        // 40% of the tangential relative speed (0.3) is killed, leaving 0.18 of relative
        // motion - split between ball and vertices the same way the normal impulse already is.
        assertEquals(0.21, store.velocity(ballId).x, 1e-9)
        for (id in listOf(triangle.a, triangle.b, triangle.c)) {
            assertEquals(0.03, store.velocity(id).x, 1e-9)
        }
    }

    @Test
    fun `total momentum is conserved with friction active, for a free unpinned system`() {
        val store = ParticleStore()
        val groups = Groups()
        val triangle = flatTriangle(store, groups, mass = 2.0)
        val ballId = ball(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.5, -5.0, -0.3), mass = 3.0)

        fun totalMomentum(): Vector3 {
            var p = Vector3.ZERO
            for (id in store.liveIds()) p += store.velocity(id) * store.mass(id)
            return p
        }

        val before = totalMomentum()
        val rule = SurfaceCollisionRule("ball", Surface(listOf(triangle)), restitution = 0.7, kineticFriction = 0.3)
        SurfaceCollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)
        val after = totalMomentum()

        assertTrue((before - after).length() < 1e-9, "expected $before but was $after")
        assertTrue(store.velocity(ballId).x != 0.5, "friction should have changed the ball's tangential velocity")
    }
}
