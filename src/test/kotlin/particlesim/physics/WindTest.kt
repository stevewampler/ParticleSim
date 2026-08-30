package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.VectorExpr
import particlesim.core.Vector3
import particlesim.surface.Triangle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §7.2: wind pressure on a triangle, and the two-sided requirement specifically. */
class WindTest {

    private fun netForceOn(id: Int, store: ParticleStore, groups: Groups, wind: Wind): Vector3 {
        val chunk = ChunkAccumulator(store.capacity)
        wind.accumulate(store, groups, 0.0, chunk, 0, 1)
        return chunk.at(store.slotOf(id))
    }

    private fun assertVectorEquals(expected: Vector3, actual: Vector3, epsilon: Double = 1e-9) {
        assertTrue(
            abs(expected.x - actual.x) < epsilon && abs(expected.y - actual.y) < epsilon && abs(expected.z - actual.z) < epsilon,
            "expected $expected, got $actual",
        )
    }

    @Test
    fun `wind perpendicular to a flat triangle splits force evenly across the three vertices`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val c = store.create(position = Vector3(0.0, 1.0, 0.0))
        val wind = Wind(listOf(Triangle(a, b, c)), VectorExpr.of(Vector3(0.0, 0.0, 5.0)), density = 1.0)

        // area = 0.5, pressure magnitude = density * area * (wind . normal) = 1 * 0.5 * 5 = 2.5,
        // split three ways.
        val expected = Vector3(0.0, 0.0, 2.5 / 3.0)
        assertVectorEquals(expected, netForceOn(a, store, groups, wind))
        assertVectorEquals(expected, netForceOn(b, store, groups, wind))
        assertVectorEquals(expected, netForceOn(c, store, groups, wind))
    }

    @Test
    fun `reversing triangle winding does not change the resulting force (two-sided, sec 7 dot 2)`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val c = store.create(position = Vector3(0.0, 1.0, 0.0))
        val windVelocity = VectorExpr.of(Vector3(1.0, -2.0, 5.0))

        val forward = Wind(listOf(Triangle(a, b, c)), windVelocity)
        val reversed = Wind(listOf(Triangle(a, c, b)), windVelocity) // b and c swapped: flips the normal

        assertVectorEquals(netForceOn(a, store, groups, forward), netForceOn(a, store, groups, reversed))
        assertVectorEquals(netForceOn(b, store, groups, forward), netForceOn(b, store, groups, reversed))
        assertVectorEquals(netForceOn(c, store, groups, forward), netForceOn(c, store, groups, reversed))
    }

    @Test
    fun `wind relative velocity accounts for the triangle's own motion`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0), velocity = Vector3(0.0, 0.0, 5.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0), velocity = Vector3(0.0, 0.0, 5.0))
        val c = store.create(position = Vector3(0.0, 1.0, 0.0), velocity = Vector3(0.0, 0.0, 5.0))
        // Surface already moving at the same velocity as the wind -> zero relative wind -> zero force.
        val wind = Wind(listOf(Triangle(a, b, c)), VectorExpr.of(Vector3(0.0, 0.0, 5.0)))

        assertEquals(Vector3.ZERO, netForceOn(a, store, groups, wind))
    }

    @Test
    fun `a degenerate zero-area triangle contributes no force`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val c = store.create(position = Vector3(2.0, 0.0, 0.0)) // collinear with a, b
        val wind = Wind(listOf(Triangle(a, b, c)), VectorExpr.of(Vector3(0.0, 0.0, 5.0)))

        assertEquals(Vector3.ZERO, netForceOn(a, store, groups, wind))
    }

    @Test
    fun `sampleAt returns the wind velocity itself, ignoring position, for the arrow renderer`() {
        val wind = Wind(emptyList(), VectorExpr.of { t -> Vector3(t, 0.0, 0.0) })
        assertEquals(Vector3(3.0, 0.0, 0.0), wind.sampleAt(Vector3(100.0, -50.0, 7.0), t = 3.0))
        assertEquals(Vector3(3.0, 0.0, 0.0), wind.sampleAt(Vector3.ZERO, t = 3.0), "position is unused - wind is spatially uniform today")
    }

    @Test
    fun `currentVelocity reflects the live evaluated value of a time-varying expression`() {
        val wind = Wind(emptyList(), VectorExpr.of { t -> Vector3(t, 0.0, 0.0) })
        assertEquals(Vector3(3.0, 0.0, 0.0), wind.currentVelocity(t = 3.0))
        assertEquals(Vector3(7.0, 0.0, 0.0), wind.currentVelocity(t = 7.0))
    }

    @Test
    fun `setVelocity replaces the expression outright, taking effect on both currentVelocity and accumulate`() {
        val store = ParticleStore()
        val groups = Groups()
        val a = store.create(position = Vector3(0.0, 0.0, 0.0))
        val b = store.create(position = Vector3(1.0, 0.0, 0.0))
        val c = store.create(position = Vector3(0.0, 1.0, 0.0))
        val wind = Wind(listOf(Triangle(a, b, c)), VectorExpr.of(Vector3(0.0, 0.0, 5.0)), density = 1.0)

        wind.setVelocity(VectorExpr.of(Vector3(0.0, 0.0, 10.0)))

        assertEquals(Vector3(0.0, 0.0, 10.0), wind.currentVelocity(t = 0.0))
        // area = 0.5, pressure magnitude = 1 * 0.5 * 10 = 5.0, split three ways - doubled from
        // the original 5.0 velocity's 2.5, proving the replacement actually drives accumulate.
        assertVectorEquals(Vector3(0.0, 0.0, 5.0 / 3.0), netForceOn(a, store, groups, wind))
    }

    @Test
    fun `setVelocity does not disturb density's independent editable field`() {
        val wind = Wind(emptyList(), VectorExpr.of(Vector3.ZERO), density = 2.5)
        wind.setVelocity(VectorExpr.of(Vector3(1.0, 0.0, 0.0)))
        assertEquals(FieldValue.Scalar(2.5), wind.editableFields()["density"])
    }
}
