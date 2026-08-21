package particlesim.expr

import kotlin.math.floor

/**
 * `noise(...)` (§4.1): seeded, deterministic **value noise** — one of the two implementations
 * the spec explicitly sanctions ("value or simplex, implementation's choice"), chosen here for
 * being far simpler to hand-roll correctly than gradient/simplex noise while still satisfying
 * the actual requirement: a smooth, deterministic, non-RNG function of its arguments. Lattice
 * points are hashed to pseudo-random values (not gradients) and interpolated with a smoothstep
 * curve — 1 to 3 arguments (1D/2D/3D), matching however many scalar expressions are passed in.
 *
 * The hash is a fixed, unseeded function of its integer lattice coordinates only — §11 doesn't
 * ask `noise()` to vary with a run's master seed (unlike emitters, §14.4), just to be a pure,
 * repeatable function of its own arguments; different-looking noise fields come from scaling/
 * offsetting the arguments themselves (`noise(t)` vs `noise(t + 91.7)`), not a separate seed.
 */
object Noise {
    // Same SplitMix64-style constants as particlesim.lifecycle.Emitter's seed mixing — not
    // shared code (different package, different purpose), but the same well-known constants.
    private const val GOLDEN_RATIO_64 = 0x9E3779B97F4A7C15UL
    private const val SPLITMIX_MULT_1 = 0xBF58476D1CE4E5B9UL
    private const val SPLITMIX_MULT_2 = 0x94D049BB133111EBUL

    fun eval(coords: DoubleArray): Double = when (coords.size) {
        1 -> noise1(coords[0])
        2 -> noise2(coords[0], coords[1])
        3 -> noise3(coords[0], coords[1], coords[2])
        else -> throw ExpressionException("noise() takes 1 to 3 arguments, got ${coords.size}")
    }

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    /** Hashes integer lattice coordinates to a pseudo-random value in [-1, 1] — a SplitMix64-
     * style mix (same technique as [particlesim.lifecycle.Emitter]'s per-emitter seeding),
     * applied here to lattice indices instead of a master seed + name. */
    private fun hash(vararg coords: Int): Double {
        var z = GOLDEN_RATIO_64.toLong()
        for (c in coords) {
            z = (z xor c.toLong()) * SPLITMIX_MULT_1.toLong()
            z = z xor (z ushr 30)
        }
        z = (z xor (z ushr 27)) * SPLITMIX_MULT_2.toLong()
        z = z xor (z ushr 31)
        // Top 53 bits -> a uniform double in [0,1), then remap to [-1,1].
        val unit = (z ushr 11).toULong().toDouble() * (1.0 / (1L shl 53).toDouble())
        return unit * 2.0 - 1.0
    }

    private fun noise1(x: Double): Double {
        val x0 = floor(x).toInt()
        val fx = fade(x - x0)
        return lerp(hash(x0), hash(x0 + 1), fx)
    }

    private fun noise2(x: Double, y: Double): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = fade(x - x0); val fy = fade(y - y0)
        val v00 = hash(x0, y0); val v10 = hash(x0 + 1, y0)
        val v01 = hash(x0, y0 + 1); val v11 = hash(x0 + 1, y0 + 1)
        return lerp(lerp(v00, v10, fx), lerp(v01, v11, fx), fy)
    }

    private fun noise3(x: Double, y: Double, z: Double): Double {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val z0 = floor(z).toInt()
        val fx = fade(x - x0); val fy = fade(y - y0); val fz = fade(z - z0)
        val v000 = hash(x0, y0, z0); val v100 = hash(x0 + 1, y0, z0)
        val v010 = hash(x0, y0 + 1, z0); val v110 = hash(x0 + 1, y0 + 1, z0)
        val v001 = hash(x0, y0, z0 + 1); val v101 = hash(x0 + 1, y0, z0 + 1)
        val v011 = hash(x0, y0 + 1, z0 + 1); val v111 = hash(x0 + 1, y0 + 1, z0 + 1)
        val lowZ = lerp(lerp(v000, v100, fx), lerp(v010, v110, fx), fy)
        val highZ = lerp(lerp(v001, v101, fx), lerp(v011, v111, fx), fy)
        return lerp(lowZ, highZ, fz)
    }
}
