package particlesim.surface

import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §15.3: closest-point-on-triangle correctness in isolation, the same "component test"
 * pattern [particlesim.collision.ColliderTest] already applies to sphere-plane/sphere-box —
 * this is the analogous geometry that [particlesim.collision.SurfaceCollisionSystem] needs. */
class TriangleClosestPointTest {

    private fun assertVectorEquals(expected: Vector3, actual: Vector3, epsilon: Double = 1e-9) {
        assertTrue((expected - actual).length() < epsilon, "expected $expected but was $actual")
    }

    private fun triangleAt(a: Vector3, b: Vector3, c: Vector3): Pair<ParticleStore, Triangle> {
        val store = ParticleStore()
        val idA = store.create(position = a)
        val idB = store.create(position = b)
        val idC = store.create(position = c)
        return store to Triangle(idA, idB, idC)
    }

    @Test
    fun `point directly above the interior projects straight down onto the face`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(0.2, 0.2, 0.5))
        assertVectorEquals(Vector3(0.2, 0.2, 0.0), result.point)
        assertEquals(1.0, result.u + result.v + result.w, 1e-9)
        assertTrue(result.u > 0.0 && result.v > 0.0 && result.w > 0.0, "interior point should have all-positive barycentrics")
    }

    @Test
    fun `point beyond vertex a's corner region snaps to vertex a`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(-1.0, -1.0, 0.3))
        assertVectorEquals(Vector3(0.0, 0.0, 0.0), result.point)
        assertEquals(1.0, result.u, 1e-9)
        assertEquals(0.0, result.v, 1e-9)
        assertEquals(0.0, result.w, 1e-9)
    }

    @Test
    fun `point beyond vertex b's corner region snaps to vertex b`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(2.0, -1.0, 0.0))
        assertVectorEquals(Vector3(1.0, 0.0, 0.0), result.point)
        assertEquals(1.0, result.v, 1e-9)
    }

    @Test
    fun `point beyond vertex c's corner region snaps to vertex c`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(-1.0, 2.0, 0.0))
        assertVectorEquals(Vector3(0.0, 1.0, 0.0), result.point)
        assertEquals(1.0, result.w, 1e-9)
    }

    @Test
    fun `point off edge ab projects onto that edge`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(0.5, -0.5, 0.0))
        assertVectorEquals(Vector3(0.5, 0.0, 0.0), result.point)
        assertEquals(0.0, result.w, 1e-9, )
        assertTrue(result.u > 0.0 && result.v > 0.0)
    }

    @Test
    fun `point off edge bc projects onto that edge`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        // bc runs from (1,0,0) to (0,1,0); midpoint (0.5,0.5,0) pushed outward along its normal (1,1)/sqrt2.
        val result = triangle.closestPoint(store, Vector3(0.5 + 0.3, 0.5 + 0.3, 0.0))
        assertVectorEquals(Vector3(0.5, 0.5, 0.0), result.point)
        assertEquals(0.0, result.u, 1e-9)
    }

    @Test
    fun `point off edge ca projects onto that edge`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val result = triangle.closestPoint(store, Vector3(-0.5, 0.5, 0.0))
        assertVectorEquals(Vector3(0.0, 0.5, 0.0), result.point)
        assertEquals(0.0, result.v, 1e-9)
    }

    @Test
    fun `barycentric weights always sum to one`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(2.0, 0.0, 0.0), Vector3(0.0, 3.0, 1.0))
        val points = listOf(
            Vector3(0.5, 0.5, 5.0), Vector3(-3.0, -3.0, 0.0), Vector3(5.0, 5.0, 5.0),
            Vector3(1.0, -1.0, 0.0), Vector3(-1.0, 1.0, 2.0),
        )
        for (p in points) {
            val result = triangle.closestPoint(store, p)
            assertEquals(1.0, result.u + result.v + result.w, 1e-9, "point $p")
        }
    }

    @Test
    fun `closest point tracks a moving vertex, since it reads live particle positions`() {
        val (store, triangle) = triangleAt(Vector3(0.0, 0.0, 0.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
        val before = triangle.closestPoint(store, Vector3(-1.0, 2.0, 0.0))
        assertVectorEquals(Vector3(0.0, 1.0, 0.0), before.point)

        store.setPosition(triangle.c, Vector3(0.0, 5.0, 0.0))
        val after = triangle.closestPoint(store, Vector3(-1.0, 2.0, 0.0))
        assertVectorEquals(Vector3(0.0, 2.0, 0.0), after.point)
    }
}
