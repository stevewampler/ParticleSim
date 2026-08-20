package particlesim.collision

import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §15.3: sphere-plane/sphere-box (and sphere-sphere) intersection correctness in isolation —
 * pure geometry, no [particlesim.core.ParticleStore] or simulation loop involved. */
class ColliderTest {

    private fun assertVectorEquals(expected: Vector3, actual: Vector3, epsilon: Double = 1e-9) {
        assertTrue(
            (expected - actual).length() < epsilon,
            "expected $expected but was $actual",
        )
    }

    // --- Plane ---------------------------------------------------------------------------

    @Test
    fun `sphere overlapping a plane reports penetration along the plane normal`() {
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val contact = plane.contact(sphereCenter = Vector3(0.0, 0.15, 0.0), sphereRadius = 0.2)
        assertTrue(contact != null)
        assertVectorEquals(Vector3(0.0, 1.0, 0.0), contact.normal)
        assertEquals(0.05, contact.penetration, 1e-9)
    }

    @Test
    fun `sphere clear of a plane reports no contact`() {
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        assertNull(plane.contact(sphereCenter = Vector3(0.0, 5.0, 0.0), sphereRadius = 0.2))
    }

    @Test
    fun `plane normal is normalized regardless of input scale`() {
        val plane = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 3.0, 0.0))
        val contact = plane.contact(sphereCenter = Vector3(0.0, 0.1, 0.0), sphereRadius = 0.2)!!
        assertVectorEquals(Vector3(0.0, 1.0, 0.0), contact.normal)
    }

    @Test
    fun `plane at an offset position uses the offset as its point`() {
        val plane = PlaneCollider(VectorExpr.of(Vector3(0.0, 5.0, 0.0)), normal = Vector3(0.0, 1.0, 0.0))
        assertNull(plane.contact(sphereCenter = Vector3(0.0, 5.1, 0.0), sphereRadius = 0.05))
        val contact = plane.contact(sphereCenter = Vector3(0.0, 5.05, 0.0), sphereRadius = 0.1)!!
        assertEquals(0.05, contact.penetration, 1e-9)
    }

    // --- Sphere ----------------------------------------------------------------------------

    @Test
    fun `overlapping spheres report penetration along the center-to-center axis`() {
        val collider = SphereCollider(VectorExpr.of(Vector3.ZERO), radius = 1.0)
        val contact = collider.contact(sphereCenter = Vector3(1.5, 0.0, 0.0), sphereRadius = 0.6)
        assertTrue(contact != null)
        assertVectorEquals(Vector3(1.0, 0.0, 0.0), contact.normal)
        assertEquals(0.1, contact.penetration, 1e-9)
    }

    @Test
    fun `separated spheres report no contact`() {
        val collider = SphereCollider(VectorExpr.of(Vector3.ZERO), radius = 1.0)
        assertNull(collider.contact(sphereCenter = Vector3(3.0, 0.0, 0.0), sphereRadius = 0.5))
    }

    @Test
    fun `coincident sphere centers still produce a finite contact`() {
        val collider = SphereCollider(VectorExpr.of(Vector3.ZERO), radius = 1.0)
        val contact = collider.contact(sphereCenter = Vector3.ZERO, sphereRadius = 0.5)!!
        assertTrue(contact.normal.isFinite() && contact.normal.length() > 0.0)
        assertEquals(1.5, contact.penetration, 1e-9)
    }

    // --- Box (axis-aligned) -----------------------------------------------------------------

    @Test
    fun `sphere overlapping a box face reports penetration along that face's normal`() {
        val box = BoxCollider(VectorExpr.of(Vector3.ZERO), halfExtents = Vector3(1.0, 1.0, 1.0))
        val contact = box.contact(sphereCenter = Vector3(1.3, 0.0, 0.0), sphereRadius = 0.5)
        assertTrue(contact != null)
        assertVectorEquals(Vector3(1.0, 0.0, 0.0), contact.normal)
        assertEquals(0.2, contact.penetration, 1e-9)
    }

    @Test
    fun `sphere clear of a box reports no contact`() {
        val box = BoxCollider(VectorExpr.of(Vector3.ZERO), halfExtents = Vector3(1.0, 1.0, 1.0))
        assertNull(box.contact(sphereCenter = Vector3(5.0, 0.0, 0.0), sphereRadius = 0.5))
    }

    @Test
    fun `sphere overlapping a box edge uses the clamped-closest-point direction`() {
        val box = BoxCollider(VectorExpr.of(Vector3.ZERO), halfExtents = Vector3(1.0, 1.0, 1.0))
        // Just outside the corner (1,1,1): closest point on the box is the corner itself.
        val center = Vector3(1.2, 1.2, 1.2)
        val contact = box.contact(sphereCenter = center, sphereRadius = 0.5)!!
        val expectedNormal = (center - Vector3(1.0, 1.0, 1.0)).normalized()
        assertVectorEquals(expectedNormal, contact.normal)
    }

    @Test
    fun `sphere center fully inside the box pushes out through the nearest face`() {
        val box = BoxCollider(VectorExpr.of(Vector3.ZERO), halfExtents = Vector3(1.0, 2.0, 3.0))
        // Nearest face to (0.9, 0, 0) is +X (distance 0.1), closer than +-Y (2.0) or +-Z (3.0).
        val contact = box.contact(sphereCenter = Vector3(0.9, 0.0, 0.0), sphereRadius = 0.3)!!
        assertVectorEquals(Vector3(1.0, 0.0, 0.0), contact.normal)
        assertEquals(0.1 + 0.3, contact.penetration, 1e-9)
    }

    // --- Moving colliders (finite-difference velocity, §12.5) ------------------------------

    @Test
    fun `collider velocity is zero before the first advance`() {
        val plane = PlaneCollider(VectorExpr.of { t -> Vector3(0.0, t, 0.0) }, normal = Vector3(0.0, 1.0, 0.0))
        assertVectorEquals(Vector3.ZERO, plane.velocity)
    }

    @Test
    fun `collider velocity is zero on its first advance even if t is nonzero`() {
        val plane = PlaneCollider(VectorExpr.of { t -> Vector3(0.0, t, 0.0) }, normal = Vector3(0.0, 1.0, 0.0))
        plane.advance(t = 5.0, dt = 0.1)
        assertVectorEquals(Vector3.ZERO, plane.velocity)
    }

    @Test
    fun `collider velocity is the finite difference of position across steps`() {
        val plane = PlaneCollider(VectorExpr.of { t -> Vector3(0.0, 2.0 * t, 0.0) }, normal = Vector3(0.0, 1.0, 0.0))
        plane.advance(t = 1.0, dt = 0.1)
        plane.advance(t = 1.1, dt = 0.1)
        // position(1.1) - position(1.0) = (0, 0.2, 0); / dt = (0, 2.0, 0), matching d/dt[2t].
        assertVectorEquals(Vector3(0.0, 2.0, 0.0), plane.velocity)
    }
}
