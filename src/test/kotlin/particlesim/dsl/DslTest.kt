package particlesim.dsl

import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DslTest {

    @Test
    fun `single declares one particle with the given fields`() {
        val sim = simulation {
            particles.single {
                position = Vector3(1.0, 2.0, 3.0)
                mass(4.0)
            }
        }
        assertEquals(1, sim.store.size)
        assertEquals(Vector3(1.0, 2.0, 3.0), sim.store.position(0))
        assertEquals(4.0, sim.store.mass(0))
    }

    @Test
    fun `grid creates rows times cols particles at the default spacing layout`() {
        val sim = simulation {
            particles.grid(rows = 2, cols = 3, spacing = 0.5)
        }
        assertEquals(6, sim.store.size)
        // row-major creation order: id = row * cols + col
        assertEquals(Vector3(0.5, 0.5, 0.0), sim.store.position(1 * 3 + 1))
    }

    @Test
    fun `grid block overrides the default position and can set a time-varying mass`() {
        val sim = simulation {
            particles.grid(rows = 1, cols = 2) { row, col ->
                position = Vector3(col * 10.0, 0.0, 0.0)
                mass { t -> 1.0 + t }
            }
        }
        assertEquals(Vector3(10.0, 0.0, 0.0), sim.store.position(1))
        assertTrue(sim.store.hasDynamicMass(0))
    }

    @Test
    fun `group tags every id returned by grid or single`() {
        val sim = simulation {
            particles.grid(rows = 1, cols = 20, spacing = 0.1) { row, col ->
                position = Vector3(col * 0.1, 2.0, 0.0)
            }.group("pole-edge")
        }
        assertEquals((0 until 20).toSet(), sim.groups.membersOf("pole-edge"))
    }
}
