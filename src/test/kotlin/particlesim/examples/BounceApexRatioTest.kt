package particlesim.examples

import particlesim.collision.CollisionSystem
import particlesim.collision.ParticleColliderRule
import particlesim.collision.PlaneCollider
import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.core.VectorExpr
import particlesim.physics.Integrator
import particlesim.physics.UniformGravity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §15.1: successive bounce-apex heights should decay as `h_n = h_0 * e^(2n)` — restitution
 * scales the outgoing speed by `e` on every bounce, and apex height is proportional to speed
 * squared (`v² = 2·g·h`), so each bounce multiplies the apex by `e²`. The ball's center
 * actually bounces at `y = radius`, not `y = 0`, so the formula is applied to the fall
 * distance above that offset (`dropHeight - radius`) and shifted back up by `radius` —
 * otherwise a nonzero radius alone produces a several-percent mismatch that has nothing to
 * do with restitution being wrong.
 *
 * Uses a *zero-damping* scenario, not §12.6's demo parameters — [buildBallBounce]'s default
 * `compressionDamping`/`extensionDamping` deliberately remove extra energy beyond restitution
 * (that's the point of the demo), which would pull the ratio away from the pure `e^(2n)`
 * curve this test checks.
 */
class BounceApexRatioTest {

    @Test
    fun `bounce apex heights decay as e squared per bounce`() {
        val dropHeight = 2.0
        val radius = 0.05
        val restitution = 0.7
        val dt = 1e-4
        val bounceCount = 4

        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(position = Vector3(0.0, dropHeight, 0.0), radius = ScalarExpr.of(radius))
        groups.add("ball", id)

        val gravity = UniformGravity("ball", Vector3(0.0, -9.8, 0.0))
        val floor = PlaneCollider(VectorExpr.of(Vector3.ZERO), normal = Vector3(0.0, 1.0, 0.0))
        val rule = ParticleColliderRule(group = "ball", collider = floor, restitution = restitution)
        val collisions = CollisionSystem(listOf(rule))
        val integrator = Integrator()

        val apexes = mutableListOf(dropHeight) // h_0, before any bounce
        var apexCandidate = dropHeight
        var prevVy = 0.0
        var t = 0.0

        while (apexes.size <= bounceCount) {
            integrator.step(store, groups, listOf(gravity), emptyList(), t, dt)
            collisions.resolve(store, groups, t, dt)
            t += dt

            val v = store.velocity(id).y
            val y = store.position(id).y

            if (v > 0.0 && prevVy <= 0.0) apexCandidate = y // just bounced: start tracking a new rise
            if (v > 0.0 && y > apexCandidate) apexCandidate = y
            if (v <= 0.0 && prevVy > 0.0) apexes += apexCandidate // just crested

            prevVy = v
        }

        for (n in 1..bounceCount) {
            // The ball's center bounces at y=radius, not y=0, so the fall distance the e^(2n)
            // decay actually applies to is (dropHeight - radius), offset back up by radius.
            val expected = radius + (dropHeight - radius) * restitution.pow(2 * n)
            val actual = apexes[n]
            val relativeError = abs(actual - expected) / expected
            assertTrue(
                relativeError < 0.03,
                "bounce $n: expected apex ~$expected, got $actual (relative error $relativeError)",
            )
        }
    }
}
