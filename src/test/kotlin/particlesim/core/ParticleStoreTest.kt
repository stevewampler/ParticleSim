package particlesim.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParticleStoreTest {

    @Test
    fun `ids are distinct and monotonically increasing`() {
        val store = ParticleStore()
        val ids = List(5) { store.create() }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `destroying a particle frees its slot for reuse without growing capacity`() {
        val store = ParticleStore()
        store.create()
        val b = store.create()
        store.create()
        val capacityBeforeDestroy = store.capacity

        store.destroy(b)
        store.create()

        assertEquals(capacityBeforeDestroy, store.capacity)
    }

    @Test
    fun `ids are never reused even when their slot is`() {
        val store = ParticleStore()
        val a = store.create()
        store.destroy(a)
        val b = store.create()
        assertTrue(b != a)
        assertFalse(store.contains(a))
        assertTrue(store.contains(b))
    }

    @Test
    fun `accessing a destroyed particle throws`() {
        val store = ParticleStore()
        val a = store.create()
        store.destroy(a)
        assertFailsWith<IllegalArgumentException> { store.position(a) }
    }

    @Test
    fun `constant mass is evaluated once and not retained as a dynamic expression`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        assertEquals(2.5, store.mass(id))
        assertFalse(store.hasDynamicMass(id))
    }

    @Test
    fun `time-varying mass is evaluated at spawnTime and retained as a dynamic expression`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of { t -> 2.0 + t }, spawnTime = 3.0)
        assertEquals(5.0, store.mass(id))
        assertTrue(store.hasDynamicMass(id))
    }

    @Test
    fun `radius and lifetime default to unset`() {
        val store = ParticleStore()
        val id = store.create()
        assertNull(store.radius(id))
        assertNull(store.lifetime(id))
    }

    @Test
    fun `radius and lifetime are set when provided`() {
        val store = ParticleStore()
        val id = store.create(radius = ScalarExpr.of(0.5), lifetime = ScalarExpr.of(10.0))
        assertEquals(0.5, store.radius(id))
        assertEquals(10.0, store.lifetime(id))
    }

    @Test
    fun `setMass replaces a constant mass outright and reports success`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        assertTrue(store.setMass(id, ScalarExpr.of(9.0), t = 0.0))
        assertEquals(9.0, store.mass(id))
        assertFalse(store.hasDynamicMass(id))
    }

    @Test
    fun `setMass with a time-varying expression demotes-to-dynamic and evaluates at t`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        assertTrue(store.setMass(id, ScalarExpr.of { t -> 2.0 + t }, t = 3.0))
        assertEquals(5.0, store.mass(id))
        assertTrue(store.hasDynamicMass(id))
    }

    @Test
    fun `setMass rejects a non-positive result and leaves mass unchanged`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        assertFalse(store.setMass(id, ScalarExpr.of(0.0), t = 0.0))
        assertFalse(store.setMass(id, ScalarExpr.of(-1.0), t = 0.0))
        assertEquals(2.5, store.mass(id))
        assertFalse(store.hasDynamicMass(id))
    }

    @Test
    fun `setMass rejects NaN or Infinite and leaves mass unchanged`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        assertFalse(store.setMass(id, ScalarExpr.of(Double.NaN), t = 0.0))
        assertFalse(store.setMass(id, ScalarExpr.of(Double.POSITIVE_INFINITY), t = 0.0))
        assertEquals(2.5, store.mass(id))
    }

    @Test
    fun `setMass rejects a dynamic expression whose value right now is non-positive`() {
        val store = ParticleStore()
        val id = store.create(mass = ScalarExpr.of(2.5))
        // Evaluates to -1.0 at t=0, even though it would be positive at other times - the edit
        // is judged by its value right now, not clamped like the per-step dynamic-mass path.
        assertFalse(store.setMass(id, ScalarExpr.of { t -> t - 1.0 }, t = 0.0))
        assertEquals(2.5, store.mass(id))
        assertFalse(store.hasDynamicMass(id))
    }

    @Test
    fun `setRadius can add a radius to a particle that had none`() {
        val store = ParticleStore()
        val id = store.create()
        assertNull(store.radius(id))
        assertTrue(store.setRadius(id, ScalarExpr.of(1.25), t = 0.0))
        assertEquals(1.25, store.radius(id))
    }

    @Test
    fun `setRadius accepts a non-positive value - no positivity guard exists for radius`() {
        val store = ParticleStore()
        val id = store.create(radius = ScalarExpr.of(0.5))
        assertTrue(store.setRadius(id, ScalarExpr.of(-1.0), t = 0.0))
        assertEquals(-1.0, store.radius(id))
    }

    @Test
    fun `setRadius rejects NaN or Infinite and leaves radius unchanged`() {
        val store = ParticleStore()
        val id = store.create(radius = ScalarExpr.of(0.5))
        assertFalse(store.setRadius(id, ScalarExpr.of(Double.NaN), t = 0.0))
        assertFalse(store.setRadius(id, ScalarExpr.of(Double.POSITIVE_INFINITY), t = 0.0))
        assertEquals(0.5, store.radius(id))
    }
}
