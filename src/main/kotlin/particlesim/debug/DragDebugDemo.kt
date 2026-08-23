package particlesim.debug

import particlesim.core.Groups
import particlesim.core.ParticleStore
import particlesim.core.ScalarExpr
import particlesim.core.Vector3
import particlesim.lifecycle.DestructionSystem
import particlesim.physics.Constraint
import particlesim.physics.Damper
import particlesim.physics.Drag
import particlesim.physics.DragConstraint
import particlesim.physics.FixedPosition
import particlesim.physics.Force
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
 *
 * **Interactive delete** (§14.2's "explicit delete via the viewer, alongside interactive
 * dragging"), via [SceneControlMessage.DeleteParticle]: double-clicking a link in the viewer
 * severs the chain there. Unlike [ParticleCollisionDebugDemo]'s balls, a chain link's deletion
 * actually has something to clean up — [DestructionSystem]'s reported `danglingForces` (the
 * Spring/Damper pair on either side of the deleted link) are pruned from both `forces` (what
 * physics actually sees) and `springs` (what the viewer draws as connecting lines), the same
 * cleanup mechanism §14.3 already established for lifetime/condition/collision-triggered
 * destroys, not a hand-rolled one-off for this demo. The result is two independently-hanging
 * pieces, not a chain that silently keeps simulating a phantom connection.
 */
fun main() {
    val store = ParticleStore()
    val groups = Groups()

    val linkCount = 12
    val spacing = 0.4
    val mass = 0.2
    var ids = (0 until linkCount).map { i ->
        store.create(position = Vector3(0.0, 4.0 - i * spacing, 0.0), mass = ScalarExpr.of(mass))
    }
    val anchorId = ids.first()
    groups.add("anchor", anchorId)
    groups.add("chain", anchorId)
    ids.drop(1).forEach { groups.add("chain", it) }

    val stiffness = 80.0
    val damping = 12.0 // above critical (2*sqrt(stiffness*mass) ~= 8.0) for the spring/damper pairs
    var springs = ids.zipWithNext { a, b -> Spring(a, b, restLength = spacing, stiffness = stiffness) }
    val dampers = ids.zipWithNext { a, b -> Damper(a, b, damping = damping) }
    val drag = Drag("chain", coefficient = 1.5) // damps bulk/pendulum-style motion Damper can't reach
    var forces: List<Force> = listOf(UniformGravity("chain", Vector3(0.0, -9.8, 0.0)), drag) + springs + dampers
    val fixedConstraints = listOf(FixedPosition("anchor", store.position(anchorId)))
    val destruction = DestructionSystem()

    val dragQueue = DragMessageQueue()
    val sceneControlQueue = SceneControlMessageQueue()
    val renderer = DebugRenderer(onTextMessage = { text ->
        DragMessage.parse(text)?.let(dragQueue::offer)
        SceneControlMessage.parse(text)?.let(sceneControlQueue::offer)
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
        for (message in sceneControlQueue.drainAll()) {
            if (message is SceneControlMessage.DeleteParticle) {
                // The pinned anchor can be deleted too - FixedPosition just becomes a no-op for
                // a group with no members left in it, no special-casing needed (§10.3's
                // collider removal already established this "let it happen" stance).
                val result = destruction.resolve(store, groups, forces, t, dt, explicitIds = setOf(message.particleId))
                if (result.destroyedIds.isNotEmpty()) {
                    val destroyedSet = result.destroyedIds.toSet()
                    ids = ids.filter { it !in destroyedSet }
                    val danglingSet = result.danglingForces.toSet()
                    forces = forces.filter { it !in danglingSet }
                    springs = springs.filter { it !in danglingSet }
                    if (activeDrag?.particleId in destroyedSet) activeDrag = null
                }
            }
        }
        repeat(stepsPerFrame) {
            for (message in dragQueue.drainAll()) {
                when (message) {
                    is DragMessage.Start -> {
                        // The pinned anchor can't be dragged - it would just fight FixedPosition.
                        // store.contains guards against a real race, not just defensively:
                        // SceneControlMessages (delete) drain once per *frame*, DragMessages
                        // drain once per physics *step* inside this same frame's repeat loop -
                        // a double-click's own stray drag_start/drag_end (see this file's own
                        // doc comment) can still be sitting queued when the frame's delete
                        // already ran, so a queued drag_start can arrive already targeting a
                        // just-destroyed id.
                        if (message.particleId != anchorId && store.contains(message.particleId)) {
                            activeDrag = DragConstraint(message.particleId, message.target)
                        }
                    }
                    is DragMessage.Move -> activeDrag?.updateTarget(message.target, dt)
                    is DragMessage.End -> {
                        activeDrag?.let { if (store.contains(it.particleId)) store.setVelocity(it.particleId, it.releaseVelocity()) }
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
