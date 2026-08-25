package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §15.3: spring/damper/drag force magnitude for known inputs, in isolation — no running simulation. */
class ForceComponentTest {

    private fun netForceOn(id: Int, store: ParticleStore, groups: Groups, force: Force): Vector3 {
        val chunk = ChunkAccumulator(store.capacity)
        force.accumulate(store, groups, 0.0, chunk, 0, 1)
        return chunk.at(store.slotOf(id))
    }

    @Test
    fun `symmetric spring pulls stretched particles together with magnitude k times displacement`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(3.0, 0.0, 0.0)) // restLength 1, stretched to 3
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0)

        val forceOnB = netForceOn(b, store, groups, spring)
        val forceOnA = netForceOn(a, store, groups, spring)

        // displacement = 2, k = 10 -> magnitude 20, pulling b back toward a (negative x)
        assertEquals(Vector3(-20.0, 0.0, 0.0), forceOnB)
        assertEquals(Vector3(20.0, 0.0, 0.0), forceOnA)
    }

    @Test
    fun `compressed spring pushes particles apart`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(0.5, 0.0, 0.0)) // restLength 1, compressed to 0.5
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0)

        val forceOnB = netForceOn(b, store, groups, spring)
        assertEquals(Vector3(5.0, 0.0, 0.0), forceOnB) // pushed further along +x, away from a
    }

    @Test
    fun `asymmetric stiffness uses compressionStiffness only when compressed`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(0.5, 0.0, 0.0)) // compressed by 0.5
        val spring = Spring(a, b, restLength = 1.0, stiffness = 10.0, compressionStiffness = 2.0)

        val forceOnB = netForceOn(b, store, groups, spring)
        assertEquals(Vector3(1.0, 0.0, 0.0), forceOnB) // 2.0 * 0.5, not 10.0 * 0.5
    }

    @Test
    fun `damper resists relative velocity along the connecting axis`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0), velocity = Vector3(1.0, 0.0, 0.0)) // separating
        val damper = Damper(a, b, damping = 3.0)

        val forceOnB = netForceOn(b, store, groups, damper)
        assertEquals(Vector3(-3.0, 0.0, 0.0), forceOnB) // opposes separation
    }

    @Test
    fun `asymmetric damping uses extensionDamping when separating`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0), velocity = Vector3(2.0, 0.0, 0.0))
        val damper = Damper(a, b, damping = 3.0, extensionDamping = 5.0)

        val forceOnB = netForceOn(b, store, groups, damper)
        assertEquals(Vector3(-10.0, 0.0, 0.0), forceOnB) // 5.0 * 2.0, not 3.0 * 2.0
    }

    @Test
    fun `linear drag opposes velocity with magnitude c times speed`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(velocity = Vector3(3.0, 4.0, 0.0)) // speed 5
        groups.add("g", id)
        val drag = Drag("g", coefficient = 2.0)

        val force = netForceOn(id, store, groups, drag)
        // magnitude = c * speed = 10, direction opposite velocity: (3,4,0)/5 * -10
        assertEquals(Vector3(-6.0, -8.0, 0.0), force)
    }

    @Test
    fun `quadratic drag scales with speed squared`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(velocity = Vector3(3.0, 4.0, 0.0)) // speed 5
        groups.add("g", id)
        val drag = Drag("g", coefficient = 2.0, quadratic = true)

        val force = netForceOn(id, store, groups, drag)
        // magnitude = c * speed^2 = 50, direction opposite velocity: (3,4,0)/5 * -50
        assertEquals(Vector3(-30.0, -40.0, 0.0), force)
    }

    @Test
    fun `uniform gravity's sampleAt returns the constant acceleration everywhere, for the arrow renderer`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0))
        assertEquals(Vector3(0.0, -9.8, 0.0), gravity.sampleAt(Vector3(50.0, 50.0, 50.0), t = 123.0))
        assertEquals(Vector3(0.0, -9.8, 0.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))
    }

    @Test
    fun `EditableFields (§10_4) - UniformGravity's acceleration is readable and settable`() {
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0))
        assertEquals(mapOf("acceleration" to FieldValue.Vector(Vector3(0.0, -9.8, 0.0))), gravity.editableFields())

        assertTrue(gravity.setField("acceleration", FieldValue.Vector(Vector3(1.0, 2.0, 3.0))))
        assertEquals(Vector3(1.0, 2.0, 3.0), gravity.sampleAt(Vector3.ZERO, t = 0.0))

        assertFalse(gravity.setField("acceleration", FieldValue.Scalar(5.0))) // wrong value kind
        assertFalse(gravity.setField("nonexistent", FieldValue.Vector(Vector3.ZERO)))
    }

    @Test
    fun `EditableFields (§10_4) - NBodyGravity exposes g and softening as independent scalars`() {
        val gravity = NBodyGravity("g", g = 1.0, softening = 2.0)
        assertEquals(mapOf("g" to FieldValue.Scalar(1.0), "softening" to FieldValue.Scalar(2.0)), gravity.editableFields())

        assertTrue(gravity.setField("g", FieldValue.Scalar(9.0)))
        assertTrue(gravity.setField("softening", FieldValue.Scalar(8.0)))
        assertEquals(mapOf("g" to FieldValue.Scalar(9.0), "softening" to FieldValue.Scalar(8.0)), gravity.editableFields())

        assertFalse(gravity.setField("g", FieldValue.Vector(Vector3.ZERO))) // wrong value kind
    }

    @Test
    fun `EditableFields (§10_4) - Wind exposes density, FixedVelocity exposes velocity`() {
        val wind = Wind(emptyList(), particlesim.core.VectorExpr.of(Vector3.ZERO), density = 1.0)
        assertEquals(mapOf("density" to FieldValue.Scalar(1.0)), wind.editableFields())
        assertTrue(wind.setField("density", FieldValue.Scalar(2.5)))
        assertEquals(mapOf("density" to FieldValue.Scalar(2.5)), wind.editableFields())

        val fixedVelocity = FixedVelocity("g", Vector3(1.0, 0.0, 0.0))
        assertEquals(mapOf("velocity" to FieldValue.Vector(Vector3(1.0, 0.0, 0.0))), fixedVelocity.editableFields())
        assertTrue(fixedVelocity.setField("velocity", FieldValue.Vector(Vector3(0.0, 5.0, 0.0))))
        assertEquals(mapOf("velocity" to FieldValue.Vector(Vector3(0.0, 5.0, 0.0))), fixedVelocity.editableFields())
    }

    @Test
    fun `group disable (§10_4) - a disabled group's field force contributes nothing`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3.ZERO)
        groups.add("g", id)
        val gravity = UniformGravity("g", Vector3(0.0, -9.8, 0.0))

        assertEquals(Vector3(0.0, -9.8, 0.0), netForceOn(id, store, groups, gravity))
        groups.setEnabled("g", false)
        assertEquals(Vector3.ZERO, netForceOn(id, store, groups, gravity))
        groups.setEnabled("g", true)
        assertEquals(Vector3(0.0, -9.8, 0.0), netForceOn(id, store, groups, gravity))
    }
}
