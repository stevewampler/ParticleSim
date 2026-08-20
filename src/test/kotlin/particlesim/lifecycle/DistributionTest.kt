package particlesim.lifecycle

import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pure geometry/statistics — no [particlesim.core.ParticleStore] or simulation loop involved. */
class DistributionTest {

    private val rng = Random(42)

    @Test
    fun `uniform box samples stay within bounds and average toward the center`() {
        val dist = VectorDistribution.UniformBox(Vector3(1.0, 2.0, 3.0), Vector3(0.5, 1.0, 2.0))
        var sum = Vector3.ZERO
        val n = 20_000
        repeat(n) {
            val p = dist.sample(rng)
            assertTrue(abs(p.x - 1.0) <= 0.5 && abs(p.y - 2.0) <= 1.0 && abs(p.z - 3.0) <= 2.0, "$p out of bounds")
            sum += p
        }
        val mean = sum * (1.0 / n)
        assertTrue((mean - Vector3(1.0, 2.0, 3.0)).length() < 0.05, "mean $mean too far from center")
    }

    @Test
    fun `uniform sphere samples stay within radius and average toward the center`() {
        val center = Vector3(-1.0, 0.5, 2.0)
        val radius = 3.0
        val dist = VectorDistribution.UniformSphere(center, radius)
        var sum = Vector3.ZERO
        val n = 20_000
        repeat(n) {
            val p = dist.sample(rng)
            assertTrue((p - center).length() <= radius + 1e-9, "$p outside radius $radius")
            sum += p
        }
        val mean = sum * (1.0 / n)
        assertTrue((mean - center).length() < 0.1, "mean $mean too far from center")
    }

    @Test
    fun `point-with-spread with zero spread always returns exactly the base direction`() {
        val dist = VectorDistribution.PointWithSpread(
            direction = Vector3(0.0, 1.0, 0.0), spreadAngleRadians = 0.0, minMagnitude = 2.0, maxMagnitude = 2.0,
        )
        repeat(50) {
            val v = dist.sample(rng)
            assertTrue((v - Vector3(0.0, 2.0, 0.0)).length() < 1e-9, "expected exactly (0,2,0), got $v")
        }
    }

    @Test
    fun `point-with-spread samples stay within the cone angle and magnitude range`() {
        val direction = Vector3(1.0, 1.0, 0.0).normalized()
        val spreadAngle = PI / 6.0 // 30 degrees
        val dist = VectorDistribution.PointWithSpread(direction, spreadAngle, minMagnitude = 1.0, maxMagnitude = 5.0)
        repeat(5_000) {
            val v = dist.sample(rng)
            val magnitude = v.length()
            assertTrue(magnitude in 1.0..5.0 + 1e-9, "magnitude $magnitude out of [1,5]")
            val cosAngle = v.normalized().dot(direction)
            assertTrue(cosAngle >= cos(spreadAngle) - 1e-9, "sample $v strayed outside the ${spreadAngle}rad cone")
        }
    }

    @Test
    fun `point-with-spread covers the full cone, not just its axis`() {
        // A wide cone should produce samples whose angle from the axis varies meaningfully,
        // not cluster near cos(theta)=1 (i.e. this isn't accidentally uniform-in-angle either,
        // but it also shouldn't collapse to "basically always near center").
        val direction = Vector3(0.0, 0.0, 1.0)
        val dist = VectorDistribution.PointWithSpread(direction, PI / 2.0, minMagnitude = 1.0, maxMagnitude = 1.0)
        var sawNearAxis = false
        var sawNearEdge = false
        repeat(5_000) {
            val cosAngle = dist.sample(rng).dot(direction)
            if (cosAngle > 0.95) sawNearAxis = true
            if (cosAngle < 0.3) sawNearEdge = true
        }
        assertTrue(sawNearAxis && sawNearEdge, "expected samples spanning near-axis and near-edge of the cone")
    }

    @Test
    fun `scalar constant always returns the same value`() {
        val dist = ScalarDistribution.Constant(7.5)
        repeat(20) { assertTrue(dist.sample(rng) == 7.5) }
    }

    @Test
    fun `scalar uniform range stays within bounds and covers the range`() {
        val dist = ScalarDistribution.UniformRange(2.0, 4.0)
        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE
        repeat(5_000) {
            val v = dist.sample(rng)
            assertTrue(v in 2.0..4.0)
            min = minOf(min, v)
            max = maxOf(max, v)
        }
        assertTrue(min < 2.2 && max > 3.8, "range not well covered: [$min, $max]")
    }
}
