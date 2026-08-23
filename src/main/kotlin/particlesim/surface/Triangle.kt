package particlesim.surface

import particlesim.core.ParticleStore
import particlesim.core.Vector3

/**
 * Three particle ids forming one face of a [Surface] (§7). Winding order matters: `(a, b,
 * c)` in that order determines which way [normal] points via the right-hand rule — callers
 * that generate triangles (e.g. [Grid.triangles]) must keep winding consistent across a
 * mesh, or neighboring faces would disagree about which way is "outward" (§7.2).
 */
data class Triangle(val a: Int, val b: Int, val c: Int) {
    /** Un-normalized normal — `edge1 x edge2`; its length is twice the triangle's area. */
    private fun crossProduct(store: ParticleStore): Vector3 {
        val posA = store.position(a)
        val edge1 = store.position(b) - posA
        val edge2 = store.position(c) - posA
        return edge1.cross(edge2)
    }

    fun area(store: ParticleStore): Double = crossProduct(store).length() * 0.5

    /** Unit normal; [Vector3.ZERO] for a degenerate (zero-area) triangle. */
    fun normal(store: ParticleStore): Vector3 = crossProduct(store).normalized()

    fun centroid(store: ParticleStore): Vector3 =
        (store.position(a) + store.position(b) + store.position(c)) * (1.0 / 3.0)

    /** Average velocity of the triangle's three vertices — used for wind-relative-to-surface (§7.2). */
    fun averageVelocity(store: ParticleStore): Vector3 =
        (store.velocity(a) + store.velocity(b) + store.velocity(c)) * (1.0 / 3.0)

    /**
     * Closest point to [point] anywhere on the triangle's surface (interior, an edge, or a
     * vertex — whichever Voronoi region [point] projects into), plus the barycentric weights
     * that express it as `u*A + v*B + w*C` — needed by [particlesim.collision.SurfaceCollisionSystem]
     * to distribute a contact impulse across the three vertices in proportion to how close each
     * one is to the actual contact (§12.4). Standard region-test algorithm (Ericson, *Real-Time
     * Collision Detection* §5.1.5); the region checks below are what make it correct at edges/
     * vertices instead of just projecting onto the (infinite) triangle plane.
     */
    fun closestPoint(store: ParticleStore, point: Vector3): TriangleClosestPoint {
        val posA = store.position(a)
        val posB = store.position(b)
        val posC = store.position(c)
        val ab = posB - posA
        val ac = posC - posA
        val ap = point - posA

        val d1 = ab.dot(ap)
        val d2 = ac.dot(ap)
        if (d1 <= 0.0 && d2 <= 0.0) return TriangleClosestPoint(posA, 1.0, 0.0, 0.0)

        val bp = point - posB
        val d3 = ab.dot(bp)
        val d4 = ac.dot(bp)
        if (d3 >= 0.0 && d4 <= d3) return TriangleClosestPoint(posB, 0.0, 1.0, 0.0)

        val vc = d1 * d4 - d3 * d2
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            val v = d1 / (d1 - d3)
            return TriangleClosestPoint(posA + ab * v, 1.0 - v, v, 0.0)
        }

        val cp = point - posC
        val d5 = ab.dot(cp)
        val d6 = ac.dot(cp)
        if (d6 >= 0.0 && d5 <= d6) return TriangleClosestPoint(posC, 0.0, 0.0, 1.0)

        val vb = d5 * d2 - d1 * d6
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            val w = d2 / (d2 - d6)
            return TriangleClosestPoint(posA + ac * w, 1.0 - w, 0.0, w)
        }

        val va = d3 * d6 - d5 * d4
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            val w = (d4 - d3) / ((d4 - d3) + (d5 - d6))
            return TriangleClosestPoint(posB + (posC - posB) * w, 0.0, 1.0 - w, w)
        }

        val denom = 1.0 / (va + vb + vc)
        val v = vb * denom
        val w = vc * denom
        return TriangleClosestPoint(posA + ab * v + ac * w, 1.0 - v - w, v, w)
    }
}

/** [Triangle.closestPoint]'s result: the closest [point], expressed as barycentric weights
 * [u]/[v]/[w] on the triangle's vertices `a`/`b`/`c` respectively (`u + v + w == 1`). */
data class TriangleClosestPoint(val point: Vector3, val u: Double, val v: Double, val w: Double)
