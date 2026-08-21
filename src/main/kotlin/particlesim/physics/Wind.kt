package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.surface.Triangle

/**
 * Wind pressure on a surface's triangles (§7.2): force is computed from wind velocity
 * relative to the triangle (accounting for the triangle's own motion), its normal, and its
 * area, then split evenly across the three vertex particles.
 *
 * `F = density * area * (relativeWind · normal) * normal` — quadratic in the normal, which
 * is what makes this **two-sided** for free: flipping a triangle's winding flips its normal
 * (`normal' = -normal`), so `(relativeWind · normal') * normal' = (-(relativeWind·normal)) *
 * (-normal) = (relativeWind·normal) * normal`, identical to the unflipped result. §7.2
 * requires this explicitly — a fluttering flag's triangles face the wind from alternating
 * sides many times a second, so the force can't assume the normal already faces the wind.
 * [WindTest] asserts this invariance directly rather than trusting the algebra.
 *
 * Strides its triangle list across chunks like [NBodyGravity]/[MeshSprings] — a surface can
 * have thousands of triangles, all one `Force`, not one `Wind` per triangle.
 */
class Wind(
    private val triangles: List<Triangle>,
    private val velocity: VectorExpr,
    private val density: Double = 1.0,
    override val name: String? = null,
) : Force, UniformFieldForce {
    /** The wind *velocity* itself (§10.2's arrow renderer target) — not the resulting
     * per-triangle pressure force [accumulate] computes, which depends on each triangle's own
     * orientation/motion and isn't a spatial field in the same sense. [position] is unused:
     * wind is spatially uniform today (§5.2 marks position-dependence optional, and Phase 2
     * confirmed the flag example never needed it) — spatially-varying gusts remain a
     * documented, unbuilt gap. */
    override fun sampleAt(position: Vector3, t: Double): Vector3 = velocity.evaluate(t)

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val wind = velocity.evaluate(t)
        var i = chunkIndex
        while (i < triangles.size) {
            val triangle = triangles[i]
            val cross = crossProduct(store, triangle)
            val area = cross.length() * 0.5
            if (area > 0.0) {
                val normal = cross * (1.0 / cross.length())
                val relativeWind = wind - triangle.averageVelocity(store)
                val pressure = normal * (density * area * relativeWind.dot(normal))
                val perVertex = pressure * (1.0 / 3.0)
                chunk.add(store.slotOf(triangle.a), perVertex)
                chunk.add(store.slotOf(triangle.b), perVertex)
                chunk.add(store.slotOf(triangle.c), perVertex)
            }
            i += chunkCount
        }
    }

    private fun crossProduct(store: ParticleStore, triangle: Triangle) =
        (store.position(triangle.b) - store.position(triangle.a))
            .cross(store.position(triangle.c) - store.position(triangle.a))
}
