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
}
