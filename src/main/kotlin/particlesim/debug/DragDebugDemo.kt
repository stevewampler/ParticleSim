package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Drag
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
 *
 * Getting this to actually settle (rather than swing indefinitely, which looked like the whole
 * chain "flying around" and made it impossible to click a dot) took two separate fixes, verified
 * by sampling positions over several seconds through a raw WebSocket client rather than assumed:
 * - **`Damper` alongside every `Spring`** (§5.1's usual pairing) damps *relative* motion between
 *   connected pairs — necessary so the springs themselves don't ring, but this alone left the
 *   whole chain gently swinging like a pendulum for many seconds, because a bulk swing barely
 *   stretches any individual spring (low relative velocity between neighbors even while the
 *   whole chain moves significantly in the lab frame), so per-pair damping barely touches it.
 * - **A `Drag` force on the whole group** damps *absolute* velocity instead, which is what
 *   actually kills that bulk pendulum mode. Both are needed — Damper alone (first attempt)
 *   wasn't enough, confirmed by watching it keep swinging for 8+ seconds without decaying.
 *
 * The starting layout also hangs each link straight down from the anchor (not bunched at one
 * height like the plain `run` demo) — that alone doesn't fix the swinging (dragging and
 * releasing re-introduces the same bulk-swing problem regardless of starting shape, which is
 * why Drag above is still needed), but it does avoid injecting a large, unnecessary initial
 * swing from the moment the demo starts.
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val linkCount = 12
    val spacing = 0.4
    val mass = 0.2
    val ids = (0 until linkCount).map { i ->
        store.create(position = Vector3(0.0, 4.0 - i * spacing, 0.0), mass = ScalarExpr.of(mass))
    }
    val anchorId = ids.first()
    groups.add("anchor", anchorId)
    groups.add("chain", anchorId)
    ids.drop(1).forEach { groups.add("chain", it) }

    val stiffness = 80.0
    val damping = 12.0 // above critical (2*sqrt(stiffness*mass) ~= 8.0) for the spring/damper pairs
    val springs = ids.zipWithNext { a, b -> Spring(a, b, restLength = spacing, stiffness = stiffness) }
    val dampers = ids.zipWithNext { a, b -> Damper(a, b, damping = damping) }
    val drag = Drag("chain", coefficient = 1.5) // damps bulk/pendulum-style motion Damper can't reach
    val forces = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0)), drag) + springs + dampers
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
