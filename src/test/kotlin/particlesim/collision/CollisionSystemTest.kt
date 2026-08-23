package particlesim.collision

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollisionSystemTest {

    private fun particle(store: ParticleStore, groups: Groups, position: Vector3, velocity: Vector3, radius: Double = 0.2): Int {
        val id = store.create(position = position, velocity = velocity, radius = ScalarExpr.of(radius))
        groups.add("ball", id)
        return id
    }

    @Test
    fun `pure restitution reflects the normal velocity component and scales it by e`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(3.5, store.velocity(id).y, 1e-9) // -e * -5.0
    }

    @Test
    fun `tangential velocity is untouched by collision response`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(2.0, -5.0, 1.5))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        val v = store.velocity(id)
        assertEquals(2.0, v.x, 1e-9)
        assertEquals(1.5, v.z, 1e-9)
    }

    @Test
    fun `compression damping further reduces the outgoing bounce speed below pure restitution`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, compressionDamping = 3.0)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        // -e*relVel / sqrt(1 + c) = 3.5 / 2.0 = 1.75, well below the undamped 3.5.
        assertEquals(1.75, store.velocity(id).y, 1e-9)
    }

    @Test
    fun `extension damping mildly reduces an already-separating but still-penetrating contact`() {
        val store = ParticleStore()
        val groups = Groups()
        // Slightly overlapping, but already moving away (e.g. leftover from correction lag).
        val id = particle(store, groups, position = Vector3(0.0, 0.19, 0.0), velocity = Vector3(0.0, 1.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(
            group = "ball", collider = plane, restitution = 0.7,
            extensionDamping = 0.2, correctionFactor = 0.0, // isolate the velocity effect
        )
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(1.0 / sqrt(1.2), store.velocity(id).y, 1e-9)
    }

    @Test
    fun `penetration correction moves the particle out by only a fraction of the overlap`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.1, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, correctionFactor = 0.2)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        // Penetration = radius(0.2) - distance(0.1) = 0.1; corrected by 20% -> +0.02.
        assertEquals(0.12, store.position(id).y, 1e-9)
    }

    @Test
    fun `resting contact clamps normal velocity to zero instead of bouncing`() {
        val store = ParticleStore()
        val groups = Groups()
        // Small penetration, small closing speed: both below the rest thresholds.
        val id = particle(store, groups, position = Vector3(0.0, 0.199, 0.0), velocity = Vector3(0.0, -0.002, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)
        val system = CollisionSystem(listOf(rule), restVelocity = 0.01, restPenetration = 0.005)

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.0, store.velocity(id).y, 1e-9)
    }

    @Test
    fun `no contact leaves the particle completely untouched`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 5.0, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(Vector3(0.0, 5.0, 0.0), store.position(id))
        assertEquals(Vector3(0.0, -5.0, 0.0), store.velocity(id))
    }

    @Test
    fun `a particle with no radius never participates in collision`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3(0.0, 0.0, 0.0), velocity = Vector3(0.0, -5.0, 0.0)) // no radius
        groups.add("ball", id)
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(Vector3(0.0, -5.0, 0.0), store.velocity(id))
    }

    @Test
    fun `restitution response against a moving collider uses relative velocity`() {
        val store = ParticleStore()
        val groups = Groups()
        // Stationary particle; a plane rises to meet it. At t=0 the plane (y=0) is nowhere
        // near the particle (y=1.0, radius 0.05), so the priming call is a genuine no-op.
        val id = particle(store, groups, position = Vector3(0.0, 1.0, 0.0), velocity = Vector3.ZERO, radius = 0.05)
        val plane = PlaneCollider(VectorExpr.of { t -> Vector3(0.0, 2.0 * t, 0.0) }, normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 1.0)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 0.1) // primes previous position; no contact yet
        assertEquals(Vector3.ZERO, store.velocity(id))
        system.resolve(store, groups, t = 0.5, dt = 0.5) // plane now at y=1.0, collider.velocity = (0, 2, 0)

        // relVel = (0 - 2) = -2 (closing); e=1 -> newRelVel = 2; particle velocity delta = normal*(2 - -2) = 4
        assertEquals(4.0, store.velocity(id).y, 1e-9)
    }

    @Test
    fun `same-location particle-vs-collider and particle-vs-sphere-collider agree`() {
        // A SphereCollider with the same radius/position as a ball the particle would otherwise
        // rest on should produce the same normal and penetration as the direct geometry.
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 1.1, 0.0), velocity = Vector3(0.0, -4.0, 0.0), radius = 0.2)
        val sphere = SphereCollider(VectorExpr.of(Vector3.ZERO), radius = 1.0)
        val rule = ParticleColliderRule(group = "ball", collider = sphere, restitution = 0.5)
        val system = CollisionSystem(listOf(rule))

        system.resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(2.0, store.velocity(id).y, 1e-9) // -0.5 * -4.0
        assertTrue(store.position(id).y > 1.1) // pushed out along +normal
    }

    // --- Friction (§12.5) --------------------------------------------------------------------

    @Test
    fun `kinetic friction partially decelerates tangential velocity during an active bounce`() {
        val store = ParticleStore()
        val groups = Groups()
        // penetration 0.05, relVel -5.0 (not resting): a real compression event.
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(2.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, kineticFriction = 0.1)
        CollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        // deltaRelVel (the normal-direction "impulse", mass-cancelled) = 3.5 - (-5.0) = 8.5;
        // friction cap = 0.1 * 8.5 = 0.85, well under the full tangential speed (2.0), so it's
        // a partial, not saturated, deceleration: vx = 2.0 - 0.85 = 1.15.
        assertEquals(1.15, store.velocity(id).x, 1e-9)
        assertEquals(3.5, store.velocity(id).y, 1e-9) // restitution unaffected by friction
    }

    @Test
    fun `kinetic friction never reverses tangential velocity, only caps it at a full stop`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(2.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        // 5.0 * 8.5 = 42.5, far more than the 2.0 m/s of tangential speed actually present.
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, kineticFriction = 5.0)
        CollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.0, store.velocity(id).x, 1e-9, "capped at zero, not driven negative")
    }

    @Test
    fun `static friction fractionally arrests a resting contact's tangential velocity, not an instant stop`() {
        val store = ParticleStore()
        val groups = Groups()
        // penetration 0.001 < restPenetration, relVel 0.005 < restVelocity: a resting contact.
        val id = particle(store, groups, position = Vector3(0.0, 0.199, 0.0), velocity = Vector3(0.5, 0.005, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, staticFriction = 0.3)
        CollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.35, store.velocity(id).x, 1e-9, "30% of tangential residual killed, not all of it")
        assertEquals(0.0, store.velocity(id).y, 1e-9, "normal component still clamped to rest as before")
    }

    @Test
    fun `static friction of 1_0 fully arrests tangential velocity in one step`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.199, 0.0), velocity = Vector3(0.5, 0.005, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, staticFriction = 1.0)
        CollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.0, store.velocity(id).x, 1e-9)
    }

    @Test
    fun `zero friction, the default, leaves tangential velocity untouched in both regimes`() {
        val store = ParticleStore()
        val groups = Groups()
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))

        val sliding = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(2.0, -5.0, 0.0))
        CollisionSystem(listOf(ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)))
            .resolve(store, groups, t = 0.0, dt = 1e-3)
        assertEquals(2.0, store.velocity(sliding).x, 1e-9)

        val resting = particle(store, groups, position = Vector3(1.0, 0.199, 0.0), velocity = Vector3(0.5, 0.005, 0.0))
        CollisionSystem(listOf(ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7)))
            .resolve(store, groups, t = 0.0, dt = 1e-3)
        assertEquals(0.5, store.velocity(resting).x, 1e-9)
    }

    @Test
    fun `friction does nothing when there is no tangential relative motion to oppose`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = particle(store, groups, position = Vector3(0.0, 0.15, 0.0), velocity = Vector3(0.0, -5.0, 0.0))
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = plane, restitution = 0.7, staticFriction = 1.0, kineticFriction = 1.0)
        CollisionSystem(listOf(rule)).resolve(store, groups, t = 0.0, dt = 1e-3)

        assertEquals(0.0, store.velocity(id).x, 1e-9)
        assertEquals(0.0, store.velocity(id).z, 1e-9)
    }
}
