package particlesim.lifecycle

import particlesim.core.Vector3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * An emitter's initial-property distribution for a vector-valued field (position, velocity —
 * §14.1). The three shapes §14.1 calls out as natively supported: a uniform-filled box, a
 * uniform-filled sphere, and a base direction with random angular spread and a magnitude
 * range (the "spray cone" shape typical of sparks/debris). Anything more exotic is left to
 * the Kotlin DSL's own procedural generators, per §14.1/§4.4 — this isn't meant to be a
 * general distribution framework.
 */
sealed interface VectorDistribution {
    fun sample(rng: Random): Vector3

    /** Uniform within an axis-aligned box centered at [center]. */
    data class UniformBox(val center: Vector3, val halfExtents: Vector3) : VectorDistribution {
        override fun sample(rng: Random): Vector3 = center + Vector3(
            (rng.nextDouble(-1.0, 1.0)) * halfExtents.x,
            (rng.nextDouble(-1.0, 1.0)) * halfExtents.y,
            (rng.nextDouble(-1.0, 1.0)) * halfExtents.z,
        )
    }

    /** Uniform within a solid sphere of [radius] centered at [center] — rejection sampling
     * within the bounding cube, not just a uniform radius (which would bias samples toward
     * the center) or a uniform direction at a fixed radius (which would only fill the shell). */
    data class UniformSphere(val center: Vector3, val radius: Double) : VectorDistribution {
        override fun sample(rng: Random): Vector3 {
            while (true) {
                val x = rng.nextDouble(-1.0, 1.0)
                val y = rng.nextDouble(-1.0, 1.0)
                val z = rng.nextDouble(-1.0, 1.0)
                if (x * x + y * y + z * z <= 1.0) return center + Vector3(x, y, z) * radius
            }
        }
    }

    /**
     * A base [direction] with a random angular spread (uniform over the solid angle within
     * [spreadAngleRadians] of it, not uniform in angle — a wide cone shouldn't over-sample its
     * edge) and a magnitude uniformly drawn from `[minMagnitude, maxMagnitude]`. `spreadAngleRadians
     * = 0` always returns exactly `direction * magnitude`. The "fixed origin plus a random
     * cone/range" shape from §14.1, most naturally used for a spark's initial velocity.
     */
    data class PointWithSpread(
        val direction: Vector3,
        val spreadAngleRadians: Double,
        val minMagnitude: Double,
        val maxMagnitude: Double,
    ) : VectorDistribution {
        private val axis = direction.normalized()
        private val tangent: Vector3
        private val bitangent: Vector3

        init {
            val reference = if (kotlin.math.abs(axis.y) < 0.99) Vector3(0.0, 1.0, 0.0) else Vector3(1.0, 0.0, 0.0)
            tangent = reference.cross(axis).normalized()
            bitangent = axis.cross(tangent)
        }

        override fun sample(rng: Random): Vector3 {
            val cosSpread = cos(spreadAngleRadians)
            val cosTheta = 1.0 - rng.nextDouble() * (1.0 - cosSpread)
            val sinTheta = sqrt((1.0 - cosTheta * cosTheta).coerceAtLeast(0.0))
            val phi = rng.nextDouble(0.0, 2.0 * Math.PI)
            val localDir = tangent * (sinTheta * cos(phi)) + bitangent * (sinTheta * sin(phi)) + axis * cosTheta
            val magnitude = if (minMagnitude == maxMagnitude) minMagnitude else rng.nextDouble(minMagnitude, maxMagnitude)
            return localDir * magnitude
        }
    }
}

/** An emitter's initial-property distribution for a scalar field (mass, radius, lifetime —
 * §14.1). Only [Constant] and [UniformRange] — "point-with-spread" collapses to a plain
 * uniform range in one dimension (`center ± spread`), so there's no separate scalar variant. */
sealed interface ScalarDistribution {
    fun sample(rng: Random): Double

    data class Constant(val value: Double) : ScalarDistribution {
        override fun sample(rng: Random): Double = value
    }

    data class UniformRange(val min: Double, val max: Double) : ScalarDistribution {
        override fun sample(rng: Random): Double = if (min == max) min else rng.nextDouble(min, max)
    }
}
