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
    private var velocity: VectorExpr,
    private var density: Double = 1.0,
    override val name: String? = null,
) : Force, UniformFieldForce, EditableFields {
    override fun editableFields(): Map<String, FieldValue> = mapOf("density" to FieldValue.Scalar(density))

    override fun setField(field: String, value: FieldValue): Boolean {
        if (field != "density" || value !is FieldValue.Scalar) return false
        density = value.value
        return true
    }

    /** requirements.md §10.4's `velocity` live-editing read path — the live evaluated vector at
     * [t] (see [currentVelocitySource] below for the companion expression-source read path).
     * Kept off [editableFields]/[FieldValue] deliberately: that mechanism has no `t` to evaluate
     * an expression against (every other `EditableFields` field today is a plain mutable number,
     * not an expression), and the edit UI this needs is a single expression-string input, not
     * the three-number x/y/z boxes a plain `FieldValue.Vector` renders as. */
    fun currentVelocity(t: Double): Vector3 = velocity.evaluate(t)

    /** §10.4's new "show the current expression source" requirement - `null` when [velocity]
     * wasn't set from a parsed expression string (its constructor default, or a native DSL
     * lambda passed directly to [VectorExpr.of]). */
    fun currentVelocitySource(): String? = velocity.source

    /** §10.4's `velocity` live-editing write path - an outright replace, same convention as
     * `ParticleStore.setMass`/`setRadius`/`Emitter.setRate`: a full replace is already the
     * time-variance-preserving option once the replacement can itself be a dynamic expression,
     * so there's no separate override layer to add. `density` is unaffected - it keeps going
     * through [editableFields]/[setField] exactly as before, unrelated to this field. */
    fun setVelocity(expr: VectorExpr) {
        velocity = expr
    }

    /** The wind *velocity* itself (§10.2's arrow renderer target) — not the resulting
     * per-triangle pressure force [accumulate] computes, which depends on each triangle's own
     * orientation/motion and isn't a spatial field in the same sense. [position] is unused:
     * wind is spatially uniform today (§5.2 marks position-dependence optional, and Phase 2
     * confirmed the flag example never needed it) — spatially-varying gusts remain a
     * documented, unbuilt gap. */
    override fun sampleAt(position: Vector3, t: Double): Vector3 = currentVelocity(t)

    override fun accumulate(
        store: ParticleStore, groups: Groups, t: Double,
        chunk: ChunkAccumulator, chunkIndex: Int, chunkCount: Int,
    ) {
        val wind = currentVelocity(t)
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
