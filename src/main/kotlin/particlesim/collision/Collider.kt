package particlesim.collision

import particlesim.core.Vector3
import particlesim.core.VectorExpr
import kotlin.math.abs

/**
 * Narrow-phase result: the surface normal to push a penetrating sphere along, and how far it
 * currently penetrates. [normal] always points away from the collider, toward the particle —
 * the direction that separates them (§12.4).
 */
data class Contact(val normal: Vector3, val penetration: Double)

/**
 * A static or moving collision primitive (§12.2). Only its position is expression-capable —
 * shape parameters (a plane's normal, a sphere's radius, a box's half-extents) are fixed at
 * construction, matching what §12.2 actually marks expression-capable.
 *
 * [advance] must be called exactly once per physics step, before any [contact] queries that
 * step, so [velocity] reflects `(pos_now - pos_prev) / dt` (§12.5) rather than symbolic
 * differentiation of [position]'s expression. It defaults to zero on the very first call,
 * before a previous position exists to difference against.
 */
sealed class Collider(private val positionExpr: VectorExpr, val name: String? = null) {
    var position: Vector3 = positionExpr.evaluate(0.0)
        private set
    var velocity: Vector3 = Vector3.ZERO
        private set
    private var hasPrevious = false

    fun advance(t: Double, dt: Double) {
        val newPosition = positionExpr.evaluate(t)
        velocity = if (hasPrevious) (newPosition - position) * (1.0 / dt) else Vector3.ZERO
        position = newPosition
        hasPrevious = true
    }

    /** Narrow-phase sphere-vs-this-shape test (§12.4) — pure geometry, no [ParticleStore] needed. */
    abstract fun contact(sphereCenter: Vector3, sphereRadius: Double): Contact?
}

/** An infinite plane through [position] (the "point") with a fixed outward [normal]. */
class PlaneCollider(
    positionExpr: VectorExpr,
    normal: Vector3,
    name: String? = null,
) : Collider(positionExpr, name) {
    // Public, matching SphereCollider's/BoxCollider's own shape fields (radius/halfExtents) -
    // the debug renderer (§10.2's "every collider as wireframe") needs it to orient a plane's
    // visual quad, the same way it already reads those other two colliders' shape fields.
    val unitNormal = normal.normalized()

    override fun contact(sphereCenter: Vector3, sphereRadius: Double): Contact? {
        val distance = (sphereCenter - position).dot(unitNormal)
        val penetration = sphereRadius - distance
        return if (penetration > 0.0) Contact(unitNormal, penetration) else null
    }
}

/** A solid sphere centered at [position] with fixed [radius]. */
class SphereCollider(
    positionExpr: VectorExpr,
    val radius: Double,
    name: String? = null,
) : Collider(positionExpr, name) {
    override fun contact(sphereCenter: Vector3, sphereRadius: Double): Contact? {
        val delta = sphereCenter - position
        val dist = delta.length()
        val penetration = (sphereRadius + radius) - dist
        if (penetration <= 0.0) return null
        // Centers coincide (dist ~ 0): no well-defined separating direction, pick an arbitrary one.
        val normal = if (dist > 1e-12) delta * (1.0 / dist) else Vector3(0.0, 1.0, 0.0)
        return Contact(normal, penetration)
    }
}

/** An axis-aligned box centered at [position] with [halfExtents] along each axis — §12.2 allows
 * "axis-aligned or oriented"; nothing else in this project has an orientation representation
 * yet, so only the axis-aligned case is implemented. */
class BoxCollider(
    positionExpr: VectorExpr,
    val halfExtents: Vector3,
    name: String? = null,
) : Collider(positionExpr, name) {
    override fun contact(sphereCenter: Vector3, sphereRadius: Double): Contact? {
        val local = sphereCenter - position
        val clamped = Vector3(
            local.x.coerceIn(-halfExtents.x, halfExtents.x),
            local.y.coerceIn(-halfExtents.y, halfExtents.y),
            local.z.coerceIn(-halfExtents.z, halfExtents.z),
        )
        val delta = local - clamped
        val dist = delta.length()
        if (dist > 1e-12) {
            val penetration = sphereRadius - dist
            return if (penetration > 0.0) Contact(delta * (1.0 / dist), penetration) else null
        }

        // Sphere center is inside the box: push out along whichever face is nearest.
        val faceDistances = doubleArrayOf(
            halfExtents.x - abs(local.x),
            halfExtents.y - abs(local.y),
            halfExtents.z - abs(local.z),
        )
        val axis = faceDistances.indices.minByOrNull { faceDistances[it] }!!
        val normal = when (axis) {
            0 -> Vector3(if (local.x >= 0.0) 1.0 else -1.0, 0.0, 0.0)
            1 -> Vector3(0.0, if (local.y >= 0.0) 1.0 else -1.0, 0.0)
            else -> Vector3(0.0, 0.0, if (local.z >= 0.0) 1.0 else -1.0)
        }
        return Contact(normal, faceDistances[axis] + sphereRadius)
    }
}
