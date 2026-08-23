package particlesim.lifecycle

import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Spring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DestructionTest {

    @Test
    fun `lifetime expiry destroys a particle once its age reaches its lifetime`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(lifetime = ScalarExpr.of(1.0), spawnTime = 0.0)
        groups.add("g", id)
        val system = DestructionSystem()

        val before = system.resolve(store, groups, emptyList(), t = 0.9, dt = 0.1)
        assertTrue(before.destroyedIds.isEmpty())
        assertTrue(store.contains(id))

        val after = system.resolve(store, groups, emptyList(), t = 1.0, dt = 0.1)
        assertEquals(listOf(id), after.destroyedIds)
        assertFalse(store.contains(id))
        assertTrue(groups.membersOf("g").isEmpty())
    }

    @Test
    fun `particles with no lifetime set are never destroyed by expiry`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create()
        groups.add("g", id)
        val system = DestructionSystem()

        system.resolve(store, groups, emptyList(), t = 1_000_000.0, dt = 1.0)
        assertTrue(store.contains(id))
    }

    @Test
    fun `expression condition destroys particles matching the predicate`() {
        val store = ParticleStore()
        val groups = Groups()
        val inside = store.create(position = Vector3(0.0, 0.0, 0.0))
        val outside = store.create(position = Vector3(100.0, 0.0, 0.0))
        groups.add("g", inside); groups.add("g", outside)

        val leftBounds = DestroyCondition("g") { s, id, _ -> s.position(id).x > 10.0 }
        val system = DestructionSystem(destroyConditions = listOf(leftBounds))

        val result = system.resolve(store, groups, emptyList(), t = 0.0, dt = 0.1)
        assertEquals(listOf(outside), result.destroyedIds)
        assertTrue(store.contains(inside))
        assertFalse(store.contains(outside))
    }

    @Test
    fun `collision-triggered destroy removes a particle that touches the collider`() {
        val store = ParticleStore()
        val groups = Groups()
        val touching = store.create(position = Vector3(0.0, 0.1, 0.0), radius = ScalarExpr.of(0.2))
        val clear = store.create(position = Vector3(0.0, 5.0, 0.0), radius = ScalarExpr.of(0.2))
        groups.add("g", touching); groups.add("g", clear)

        val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val system = DestructionSystem(collisionDestroyRules = listOf(CollisionDestroyRule("g", floor)))

        val result = system.resolve(store, groups, emptyList(), t = 0.0, dt = 1e-3)
        assertEquals(listOf(touching), result.destroyedIds)
        assertTrue(store.contains(clear))
    }

    @Test
    fun `a particle with no radius never triggers collision destroy`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3(0.0, 0.0, 0.0)) // no radius
        groups.add("g", id)
        val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val system = DestructionSystem(collisionDestroyRules = listOf(CollisionDestroyRule("g", floor)))

        system.resolve(store, groups, emptyList(), t = 0.0, dt = 1e-3)
        assertTrue(store.contains(id))
    }

    @Test
    fun `dangling pairwise forces referencing a destroyed particle are reported, not silently left`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(lifetime = ScalarExpr.of(1.0), spawnTime = 0.0)
        val b = store.create()
        groups.add("g", a); groups.add("g", b)
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0)

        val system = DestructionSystem()
        val result = system.resolve(store, groups, listOf(spring), t = 1.0, dt = 0.1)

        assertEquals(listOf(a), result.destroyedIds)
        assertEquals(listOf(spring), result.danglingForces)
    }

    @Test
    fun `a pairwise force between two unaffected particles is never reported as dangling`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create()
        val b = store.create()
        val doomed = store.create(lifetime = ScalarExpr.of(1.0), spawnTime = 0.0)
        groups.add("g", a); groups.add("g", b); groups.add("g", doomed)
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0)

        val system = DestructionSystem()
        val result = system.resolve(store, groups, listOf(spring), t = 1.0, dt = 0.1)

        assertEquals(listOf(doomed), result.destroyedIds)
        assertTrue(result.danglingForces.isEmpty())
    }

    // --- Explicit/interactive destroy (§14.2's fourth mechanism) ----------------------------

    @Test
    fun `an explicit id destroys that particle even with no lifetime, condition, or collision at all`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create()
        groups.add("g", id)
        val system = DestructionSystem()

        val result = system.resolve(store, groups, emptyList(), t = 0.0, dt = 0.1, explicitIds = setOf(id))

        assertEquals(listOf(id), result.destroyedIds)
        assertFalse(store.contains(id))
        assertTrue(groups.membersOf("g").isEmpty())
    }

    @Test
    fun `an explicit id for an already-dead particle is silently ignored, not an error`() {
        val store = ParticleStore()
        val groups = Groups()
        val system = DestructionSystem()

        val result = system.resolve(store, groups, emptyList(), t = 0.0, dt = 0.1, explicitIds = setOf(999))

        assertTrue(result.destroyedIds.isEmpty())
    }

    @Test
    fun `an explicit delete reports dangling pairwise forces exactly like any other destroy trigger`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create()
        val b = store.create()
        groups.add("g", a); groups.add("g", b)
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0)
        val system = DestructionSystem()

        val result = system.resolve(store, groups, listOf(spring), t = 0.0, dt = 0.1, explicitIds = setOf(a))

        assertEquals(listOf(a), result.destroyedIds)
        assertEquals(listOf(spring), result.danglingForces)
    }

    @Test
    fun `an explicit id and an unrelated destroy mechanism in the same step don't double-report`() {
        val store = ParticleStore()
        val groups = Groups()
        val explicit = store.create()
        val expired = store.create(lifetime = ScalarExpr.of(1.0), spawnTime = 0.0)
        groups.add("g", explicit); groups.add("g", expired)
        val system = DestructionSystem()

        val result = system.resolve(store, groups, emptyList(), t = 1.0, dt = 0.1, explicitIds = setOf(explicit))

        assertEquals(setOf(explicit, expired), result.destroyedIds.toSet())
        assertEquals(2, result.destroyedIds.size)
    }

    @Test
    fun `a particle matched by multiple destroy mechanisms in one step is only destroyed once`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3(0.0, 0.1, 0.0), radius = ScalarExpr.of(0.2), lifetime = ScalarExpr.of(1.0), spawnTime = 0.0)
        groups.add("g", id)

        val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val system = DestructionSystem(collisionDestroyRules = listOf(CollisionDestroyRule("g", floor)))

        val result = system.resolve(store, groups, emptyList(), t = 1.0, dt = 0.1)
        assertEquals(listOf(id), result.destroyedIds)
    }
}
