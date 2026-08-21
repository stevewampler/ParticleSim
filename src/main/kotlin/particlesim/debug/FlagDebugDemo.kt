package particlesim.debug

import particlesim.core.Vector3
import particlesim.examples.FLAG_DT
import particlesim.examples.buildFlag
import particlesim.physics.Constraint
import particlesim.physics.DragConstraint
import particlesim.physics.Integrator
import particlesim.render.CameraFunction
import particlesim.render.CameraPose
import particlesim.render.SceneQueryImpl
import kotlin.math.cos
import kotlin.math.sin

/**
 * A visible run of §7.3's flag worked example through Phase 3's debug renderer: `./gradlew
 * runFlagDemo`, then open the URL it prints. Only structural-edge lines are drawn (not
 * shear/bend) — those are diagonal/skip-vertex connections that would clutter a debug
 * wireframe view without adding to its readability as "a flag".
 *
 * Also §10.1's first worked example: a scripted camera orbiting the cloth's centroid while
 * looking at the free corner (farthest from the pole, so the most visually dynamic point) —
 * almost exactly the requirements doc's own motivating example for camera scripting.
 *
 * And §9.4's drag, wired the same way `DragDebugDemo` already does it — a cloth particle can
 * be grabbed and pulled, same as the spring-chain's, but here it demonstrates propagation
 * through a whole sheet instead of a line.
 */
fun main() {
    val scenario = buildFlag(rows = 8, cols = 14)
    val structural = scenario.meshSprings[0]
    val flagTip = scenario.grid.last().last()
    val scene = SceneQueryImpl(scenario.store, scenario.groups)
    val camera = CameraFunction { t, s ->
        CameraPose(
            position = s.centroid("cloth") + Vector3(sin(t * 0.3) * 5.0, 2.0, cos(t * 0.3) * 5.0),
            lookAt = s.position(flagTip),
        )
    }

    val dragQueue = DragMessageQueue()
    val renderer = DebugRenderer(onTextMessage = { text ->
        DragMessage.parse(text)?.let(dragQueue::offer)
    })
    renderer.start()

    val integrator = Integrator()
    val framesPerSecond = 60
    val stepsPerFrame = maxOf(1, ((1.0 / framesPerSecond) / FLAG_DT).toInt())

    var t = 0.0
    var step = 0L
    var activeDrag: DragConstraint? = null
    val frameNanos = 1_000_000_000L / framesPerSecond
    val allIds = scenario.grid.flatten()
    while (true) {
        val frameStart = System.nanoTime()
        repeat(stepsPerFrame) {
            for (message in dragQueue.drainAll()) {
                when (message) {
                    is DragMessage.Start -> {
                        // The pole edge is already FixedPosition-constrained — dragging it
                        // would just fight that constraint, same reasoning as DragDebugDemo
                        // excluding its own pinned anchor.
                        if ("pole" !in scenario.groups.groupsOf(message.particleId)) {
                            activeDrag = DragConstraint(message.particleId, message.target)
                        }
                    }
                    is DragMessage.Move -> activeDrag?.updateTarget(message.target, FLAG_DT)
                    is DragMessage.End -> {
                        activeDrag?.let { scenario.store.setVelocity(it.particleId, it.releaseVelocity()) }
                        activeDrag = null
                    }
                }
            }
            val constraints: List<Constraint> = activeDrag?.let { scenario.constraints + it } ?: scenario.constraints
            integrator.step(scenario.store, scenario.groups, scenario.forces, constraints, t, FLAG_DT)
            t += FLAG_DT
            step++
        }
        renderer.broadcast(t, step, scenario.store, allIds, structural.activeConnections(), camera.evaluate(t, scene))
        val elapsed = System.nanoTime() - frameStart
        if (elapsed < frameNanos) Thread.sleep((frameNanos - elapsed) / 1_000_000)
    }
}
