package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.test.Test
import kotlin.test.assertEquals

class FlagpoleTest {

    @Test
    fun `pole particles span base to top, evenly spaced`() {
        val pole = buildFlagpole(height = 3.0, segments = 6)
        assertEquals(7, pole.poleIds.size) // segments + 1 endpoints

        val positions = pole.poleIds.map { pole.store.position(it) }
        assertEquals(Vector3(0.0, 0.0, 0.0), positions.first())
        assertEquals(Vector3(0.0, 3.0, 0.0), positions.last())
        assertEquals(Vector3(0.0, 1.5, 0.0), positions[3]) // midpoint
    }

    @Test
    fun `the placement offset is the pole's base, not its top`() {
        val pole = buildFlagpole(height = 2.0, segments = 2, placement = ShapePlacement(offset = Vector3(5.0, 0.0, 1.0)))
        assertEquals(Vector3(5.0, 0.0, 1.0), pole.store.position(pole.poleIds.first()))
        assertEquals(Vector3(5.0, 2.0, 1.0), pole.store.position(pole.poleIds.last()))
    }

    @Test
    fun `pole particles never move under stepping, even with no forces applied`() {
        val pole = buildFlagpole(height = 3.0, segments = 4)
        val before = pole.poleIds.map { pole.store.position(it) }

        val integrator = Integrator()
        var t = 0.0
        repeat(1000) {
            integrator.step(pole.store, pole.groups, emptyList(), pole.constraints, t, 1e-3)
            t += 1e-3
        }

        val after = pole.poleIds.map { pole.store.position(it) }
        assertEquals(before, after)
    }

    @Test
    fun `an instance name namespaces the pole group`() {
        val pole = buildFlagpole(placement = ShapePlacement(instanceName = "pole1"))
        assertEquals(setOf("pole1.pole"), pole.groups.groupsOf(pole.poleIds.first()))
    }
}
