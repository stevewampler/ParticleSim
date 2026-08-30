package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals

class ConstraintTest {

    @Test
    fun `fixed position wins over gravity and zeroes velocity`() {
        val store = ParticleStore()
        val groups = Groups()
        val anchored = store.create(position = Vector3(0.0, 5.0, 0.0))
        groups.add("anchored", anchored)

        val gravity = UniformGravity("anchored", Vector3(0.0, -9.8, 0.0))
        val constraint = FixedPosition("anchored", Vector3(0.0, 5.0, 0.0))
        val integrator = Integrator()

        var t = 0.0
        repeat(100) {
            integrator.step(store, groups, listOf(gravity), listOf(constraint), t, 0.01)
            t += 0.01
        }

        assertEquals(Vector3(0.0, 5.0, 0.0), store.position(anchored))
        assertEquals(Vector3.ZERO, store.velocity(anchored))
    }

    @Test
    fun `fixed velocity is integrated into position despite gravity`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3.ZERO)
        groups.add("driven", id)

        val gravity = UniformGravity("driven", Vector3(0.0, -9.8, 0.0))
        val constraint = FixedVelocity("driven", Vector3(1.0, 0.0, 0.0))
        val integrator = Integrator()

        integrator.step(store, groups, listOf(gravity), listOf(constraint), 0.0, 0.1)

        assertEquals(Vector3(1.0, 0.0, 0.0), store.velocity(id))
        assertEquals(Vector3(0.1, 0.0, 0.0), store.position(id))
    }

    @Test
    fun `drag constraint pins position and zeroes velocity, overriding gravity and connected springs`() {
        val store = ParticleStore()
        val groups = Groups()
        val dragged = store.create(position = Vector3(0.0, 5.0, 0.0))
        val anchor = store.create(position = Vector3(1.0, 5.0, 0.0))
        groups.add("free", dragged)
        groups.add("anchor", anchor)

        val gravity = UniformGravity("free", Vector3(0.0, -9.8, 0.0))
        val spring = Spring(dragged, anchor, restLength = 1.0, stiffness = 50.0)
        val drag = DragConstraint(dragged, Vector3(0.0, 5.0, 0.0))
        val anchorConstraint = FixedPosition("anchor", Vector3(1.0, 5.0, 0.0))
        val integrator = Integrator()

        var t = 0.0
        repeat(50) {
            integrator.step(store, groups, listOf(gravity, spring), listOf(drag, anchorConstraint), t, 0.01)
            t += 0.01
        }

        assertEquals(Vector3(0.0, 5.0, 0.0), store.position(dragged))
        assertEquals(Vector3.ZERO, store.velocity(dragged))
    }

    @Test
    fun `drag constraint follows an updated target on the very next step`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3.ZERO)
        groups.add("dragged", id)

        val drag = DragConstraint(id, Vector3.ZERO)
        val integrator = Integrator()

        integrator.step(store, groups, emptyList(), listOf(drag), 0.0, 0.01)
        assertEquals(Vector3.ZERO, store.position(id))

        drag.updateTarget(Vector3(2.0, 0.0, 0.0), 0.01)
        integrator.step(store, groups, emptyList(), listOf(drag), 0.01, 0.01)
        assertEquals(Vector3(2.0, 0.0, 0.0), store.position(id))
    }

    @Test
    fun `release velocity is the finite difference of the two most recent targets`() {
        val drag = DragConstraint(0, Vector3(0.0, 0.0, 0.0))
        assertEquals(Vector3.ZERO, drag.releaseVelocity(), "never moved yet -> zero")

        drag.updateTarget(Vector3(1.0, 0.0, 0.0), dt = 0.1)
        assertEquals(Vector3(10.0, 0.0, 0.0), drag.releaseVelocity())

        drag.updateTarget(Vector3(1.0, 0.5, 0.0), dt = 0.05)
        assertEquals(Vector3(0.0, 10.0, 0.0), drag.releaseVelocity())
    }

    @Test
    fun `name defaults to null and is threaded through both FixedPosition construction paths`() {
        assertEquals(null, FixedPosition("g", Vector3.ZERO).name)
        assertEquals("anchor", FixedPosition("g", Vector3.ZERO, name = "anchor").name)

        val store = ParticleStore()
        val groups = Groups()
        groups.add("g", store.create())
        assertEquals("pole", FixedPosition.atCurrentPositions("g", store, groups, name = "pole").name)
        assertEquals(null, FixedPosition.atCurrentPositions("g", store, groups).name)
    }

    @Test
    fun `FixedPosition's shared-position variant exposes an editable position field`() {
        val shared = FixedPosition("g", Vector3(1.0, 2.0, 3.0))
        assertEquals(mapOf("position" to FieldValue.Vector(Vector3(1.0, 2.0, 3.0))), shared.editableFields())

        assertEquals(true, shared.setField("position", FieldValue.Vector(Vector3(4.0, 5.0, 6.0))))
        assertEquals(mapOf("position" to FieldValue.Vector(Vector3(4.0, 5.0, 6.0))), shared.editableFields())

        assertEquals(false, shared.setField("position", FieldValue.Scalar(1.0)), "wrong value kind")
        assertEquals(false, shared.setField("bogus", FieldValue.Vector(Vector3.ZERO)), "unknown field name")
    }

    @Test
    fun `FixedPosition's per-particle-pinned variant is view-only, per requirements md §10_4`() {
        val store = ParticleStore()
        val groups = Groups()
        groups.add("g", store.create())
        val perParticle = FixedPosition.atCurrentPositions("g", store, groups)

        assertEquals(emptyMap(), perParticle.editableFields())
        assertEquals(false, perParticle.setField("position", FieldValue.Vector(Vector3.ZERO)))
    }

    @Test
    fun `FixedVelocity name defaults to null`() {
        assertEquals(null, FixedVelocity("g", Vector3.ZERO).name)
        assertEquals("driven", FixedVelocity("g", Vector3.ZERO, name = "driven").name)
    }

    @Test
    fun `DragConstraint is always unnamed - a drag session is never scene-authored identity`() {
        assertEquals(null, DragConstraint(0, Vector3.ZERO).name)
    }
}
