package particlesim.physics

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** §15.1: uniform gravity only, apex height and range against `v²sin²θ/2g` and `v²sin2θ/g`. */
class ProjectileMotionTest {

    @Test
    fun `apex height and range match the closed-form projectile equations`() {
        val g = 9.8
        val speed = 20.0
        val angle = PI / 4.0 // 45 degrees maximizes range, keeps both terms well-scaled
        val dt = 1e-4

        val store = ParticleStore()
        val groups = Groups()
        val id = store.create(
            position = Vector3.ZERO,
            velocity = Vector3(speed * kotlin.math.cos(angle), speed * sin(angle), 0.0),
        )
        groups.add("all", id)

        val gravity = UniformGravity("all", Vector3(0.0, -g, 0.0))
        val integrator = Integrator()

        var t = 0.0
        var apex = 0.0
        var landedAtX = 0.0
        var prevY = store.position(id).y
        while (true) {
            integrator.step(store, groups, listOf(gravity), emptyList(), t, dt)
            t += dt
            val p = store.position(id)
            if (p.y > apex) apex = p.y
            if (prevY >= 0.0 && p.y < 0.0) {
                landedAtX = p.x
                break
            }
            prevY = p.y
        }

        val expectedApex = speed * speed * sin(angle) * sin(angle) / (2.0 * g)
        val expectedRange = speed * speed * sin(2.0 * angle) / g

        assertTrue(abs(apex - expectedApex) / expectedApex < 1e-3, "expected apex $expectedApex, got $apex")
        assertTrue(abs(landedAtX - expectedRange) / expectedRange < 1e-3, "expected range $expectedRange, got $landedAtX")
    }
}
