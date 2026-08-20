package particlesim.core

import kotlin.math.sqrt

/** A 3D vector (m, m/s, etc. depending on context) — §3. */
data class Vector3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scalar: Double) = Vector3(x * scalar, y * scalar, z * scalar)
    operator fun unaryMinus() = Vector3(-x, -y, -z)

    fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    /** Right-handed cross product (§11: right-handed, +Y up) — used for triangle normals (§7.2). */
    fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun lengthSquared(): Double = dot(this)
    fun length(): Double = sqrt(lengthSquared())

    fun normalized(): Vector3 {
        val len = length()
        return if (len == 0.0) ZERO else this * (1.0 / len)
    }

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
    }
}
