package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.FixedPosition
import particlesim.physics.Integrator
import particlesim.physics.Spring
import particlesim.physics.UniformGravity

/**
 * A visible run of Phase 3's debug renderer: a chain of particles connected by springs, one
 * end pinned, swinging under gravity — enough to see dots (particles) and lines (the
 * springs) both moving. `./gradlew run`, then open the URL it prints.
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val linkCount = 12
    val spacing = 0.4
    val ids = (0 until linkCount).map { i ->
        store.create(
            position = Vector3(i * spacing, 4.0, 0.0),
            // A bit of out-of-plane velocity on the free end (§11: right-handed, +Y up) so the
            // chain swings in z as well as x/y — a viewer bug that swapped y/z axes would still
            // look plausible with a purely-XY motion, so this is what makes a visual check able
            // to actually catch that class of bug.
            velocity = if (i == linkCount - 1) Vector3(0.0, 0.0, 1.5) else Vector3.ZERO,
            mass = ScalarExpr.of(0.2),
        )
    }
    groups.add("anchor", ids.first())
    groups.add("chain", ids.first())
    ids.drop(1).forEach { groups.add("chain", it) }

    val springs = ids.zipWithNext { a, b -> Spring(a, b, restLength = spacing, stiffness = 80.0) }
    val forces = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0))) + springs
    val constraints = listOf(FixedPosition("anchor", store.position(ids.first())))

    val renderer = DebugRenderer()
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    val frameNanos = 1_000_000_000L / framesPerSecond
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            integrator.step(store, groups, forces, constraints, t, dt)
            t += dt
            step++
        }
        renderer.broadcast(t, step, store, ids, springs.map { it.particleA to it.particleB })
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
