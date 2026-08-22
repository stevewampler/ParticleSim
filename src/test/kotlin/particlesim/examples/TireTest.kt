package particlesim.examples

import particlesim.core.Vector3
import particlesim.physics.Integrator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TireTest {

    @Test
    fun `rim particles are evenly spaced around the circle at dropHeight`() {
        val tire = buildTire(radius = 2.0, segments = 8, dropHeight = 5.0)
        assertEquals(8, tire.rimIds.size)

        tire.rimIds.forEachIndexed { i, id ->
            val expectedAngle = 2.0 * PI * i / 8
            val expected = Vector3(2.0 * cos(expectedAngle), 5.0, 2.0 * sin(expectedAngle))
            val actual = tire.store.position(id)
            assertEquals(expected.x, actual.x, 1e-9)
            assertEquals(expected.y, actual.y, 1e-9)
            assertEquals(expected.z, actual.z, 1e-9)
        }
    }

    @Test
    fun `an odd segment count is rejected, since diameter bracing needs an exact opposite`() {
        assertFailsWith<IllegalArgumentException> { buildTire(segments = 7) }
    }

    @Test
    fun `too few segments is rejected`() {
        assertFailsWith<IllegalArgumentException> { buildTire(segments = 2) }
    }

    @Test
    fun `the placement offset applies to every rim particle`() {
        val offset = Vector3(3.0, 0.0, -1.0)
        val plain = buildTire(radius = 1.0, segments = 6, dropHeight = 2.0)
        val offsetTire = buildTire(radius = 1.0, segments = 6, dropHeight = 2.0, placement = ShapePlacement(offset = offset))

        plain.rimIds.zip(offsetTire.rimIds).forEach { (a, b) ->
            assertEquals(plain.store.position(a) + offset, offsetTire.store.position(b))
        }
    }

    @Test
    fun `dropped onto the ground, the tire falls, deforms, and settles near its own particle radius`() {
        val tire = buildTire(radius = 1.0, segments = 12, dropHeight = 2.0, particleRadius = 0.05)
        val integrator = Integrator()

        var t = 0.0
        val dt = TIRE_DT
        val initialAverageY = tire.rimIds.map { tire.store.position(it).y }.average()

        repeat(8000) { // 8 seconds - long enough to land and settle
            integrator.step(tire.store, tire.groups, tire.forces, emptyList(), t, dt)
            tire.collisions.resolve(tire.store, tire.groups, t, dt)
            t += dt
        }

        val finalPositions = tire.rimIds.map { tire.store.position(it) }
        val finalAverageY = finalPositions.map { it.y }.average()

        assertTrue(finalAverageY < initialAverageY, "the tire should have fallen from its drop height")
        // Every rim particle should have settled close to the ground (within its own radius,
        // with some margin for the ring's own vertical extent/wobble once resting).
        finalPositions.forEach { p ->
            assertTrue(p.y in -0.1..0.5, "expected particle to have settled near the ground, was at y=${p.y}")
        }
        // Nothing should have flown off to infinity or NaN'd.
        finalPositions.forEach { p -> assertTrue(p.isFinite(), "particle position should stay finite: $p") }
    }

    @Test
    fun `an instance name namespaces the rim group`() {
        val tire = buildTire(placement = ShapePlacement(instanceName = "tire1"))
        assertEquals(setOf("tire1.rim"), tire.groups.groupsOf(tire.rimIds.first()))
    }
}
