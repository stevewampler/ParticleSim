package particlesim.render

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals

class SceneQueryTest {

    @Test
    fun `position reads a particle's current position`() {
        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3(1.0, 2.0, 3.0))
        val scene = SceneQueryImpl(store, groups)

        assertEquals(Vector3(1.0, 2.0, 3.0), scene.position(id))

        store.setPosition(id, Vector3(4.0, 5.0, 6.0))
        assertEquals(Vector3(4.0, 5.0, 6.0), scene.position(id), "reads live state, not a snapshot")
    }

    @Test
    fun `centroid averages every current member's position`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(2.0, 0.0, 0.0))
        val c = store.create(position = Vector3(1.0, 3.0, 0.0))
        groups.add("g", a)
        groups.add("g", b)
        groups.add("g", c)
        val scene = SceneQueryImpl(store, groups)

        assertEquals(Vector3(1.0, 1.0, 0.0), scene.centroid("g"))
    }

    @Test
    fun `centroid of an empty or unknown group is zero, not a crash`() {
        val store = ParticleStore()
        val groups = Groups()
        val scene = SceneQueryImpl(store, groups)

        assertEquals(Vector3.ZERO, scene.centroid("never-declared"))
    }
}
