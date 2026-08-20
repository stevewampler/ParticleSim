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
}
