package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.DragConstraint
import particlesim.physics.FixedPosition
import particlesim.physics.Integrator
import particlesim.physics.Spring
import particlesim.physics.UniformGravity

/**
 * §9.4's interactive drag worked example: the same spring-chain scenario as the default `run`
 * demo (one end pinned, hanging under gravity), extended so any free particle can be
 * click-dragged — the motion propagates through the springs to the rest of the chain, exactly
 * the "probe a simulation's behavior" scenario §9.4 itself uses to motivate the feature.
 * `./gradlew runDragDemo`, then open the URL it prints: click and drag a dot (not the pinned
 * one at the far left), release to throw it.
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val linkCount = 12
    val spacing = 0.4
    val ids = (0 until linkCount).map { i ->
        store.create(position = Vector3(i * spacing, 4.0, 0.0), mass = ScalarExpr.of(0.2))
    }
    val anchorId = ids.first()
    groups.add("anchor", anchorId)
    groups.add("chain", anchorId)
    ids.drop(1).forEach { groups.add("chain", it) }

    val springs = ids.zipWithNext { a, b -> Spring(a, b, restLength = spacing, stiffness = 80.0) }
    val forces = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0))) + springs
    val fixedConstraints = listOf(FixedPosition("anchor", store.position(anchorId)))

    val dragQueue = DragMessageQueue()
    val renderer = DebugRenderer(onTextMessage = { text ->
        DragMessage.parse(text)?.let(dragQueue::offer)
    })
    renderer.start()

    val integrator = Integrator()
    val dt = 1e-3
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / dt).toInt())

    var t = 0.0
    var step = 0L
    var activeDrag: DragConstraint? = null
    val frameNanos = 1_000_000_000L / framesPerSecond

    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            for (message in dragQueue.drainAll()) {
                when (message) {
                    is DragMessage.Start -> {
                        // The pinned anchor can't be dragged — it would just fight FixedPosition.
                        if (message.particleId != anchorId) {
                            activeDrag = DragConstraint(message.particleId, message.target)
                        }
                    }
                    is DragMessage.Move -> activeDrag?.updateTarget(message.target, dt)
                    is DragMessage.End -> {
                        activeDrag?.let { store.setVelocity(it.particleId, it.releaseVelocity()) }
                        activeDrag = null
                    }
                }
            }
            val constraints: List<Constraint> = activeDrag?.let { fixedConstraints + it } ?: fixedConstraints
            integrator.step(store, groups, forces, constraints, t, dt)
            t += dt
            step++
        }
        renderer.broadcast(t, step, store, ids, springs.map { it.particleA to it.particleB })
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
